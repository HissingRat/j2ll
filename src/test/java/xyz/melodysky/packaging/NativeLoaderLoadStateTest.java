package xyz.melodysky.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

class NativeLoaderLoadStateTest {
    private static final int UNLOADED = 0;
    private static final int LOADING = 1;
    private static final int READY = 2;
    private static final int FAILED = 3;

    @AfterEach
    void resetLoadHook() {
        NativeLoaderLoadHook.reset();
    }

    @Test
    void emitsFailClosedFourStateLoadMachine() throws Exception {
        ClassNode loader = read(generatedLoader());
        FieldNode state = field(loader, "loadState", "I");
        MethodNode ensureLoaded = method(loader, "ensureLoaded", "()V");

        assertNotNull(state);
        assertTrue((state.access & Opcodes.ACC_PRIVATE) != 0);
        assertTrue((state.access & Opcodes.ACC_STATIC) != 0);
        assertTrue((state.access & Opcodes.ACC_VOLATILE) != 0);
        assertFalse(loader.fields.stream().anyMatch(candidate ->
                candidate.name.equals("loaded") && candidate.desc.equals("Z")));
        assertTrue((ensureLoaded.access & Opcodes.ACC_SYNCHRONIZED) != 0);
        assertEquals(
                List.of("UNLOADED", "LOADING", "READY", "FAILED"),
                loader.fields.stream()
                        .filter(candidate -> candidate.desc.equals("I")
                                && (candidate.access & Opcodes.ACC_STATIC) != 0
                                && (candidate.access & Opcodes.ACC_FINAL) != 0)
                        .map(candidate -> candidate.name)
                        .toList());
        assertEquals(UNLOADED, field(loader, "UNLOADED", "I").value);
        assertEquals(LOADING, field(loader, "LOADING", "I").value);
        assertEquals(READY, field(loader, "READY", "I").value);
        assertEquals(FAILED, field(loader, "FAILED", "I").value);
        assertEquals(1, ensureLoaded.tryCatchBlocks.size());
        assertEquals("java/lang/Throwable", ensureLoaded.tryCatchBlocks.getFirst().type);

        int loadingWrite = stateWriteAfterConstant(ensureLoaded, LOADING, 0);
        int loadCall = instructionIndex(ensureLoaded, instruction -> instruction instanceof MethodInsnNode call
                && call.name.equals("loadForCurrentTarget"));
        int readyWrite = stateWriteAfterConstant(ensureLoaded, READY, loadingWrite + 1);
        int failedWrite = stateWriteAfterConstant(ensureLoaded, FAILED, readyWrite + 1);
        assertTrue(loadingWrite < loadCall, "LOADING must be visible before native loading starts");
        assertTrue(loadCall < readyWrite, "READY must only be written after native loading returns");
        assertTrue(readyWrite < failedWrite, "Throwable handler must write FAILED after the success path");
        assertTrue(hasRethrowAfter(ensureLoaded, failedWrite));
    }

    @Test
    void readyIsPublishedOnlyAfterLoadReturnsAndRepeatedCallsDoNotReload() throws Exception {
        NativeLoaderLoadHook.action(anchor -> assertEquals(
                LOADING,
                loadState(anchor),
                "load action must observe LOADING, never READY"));
        Class<?> loader = defineWithTestLoadHook();

        invokeEnsureLoaded(loader);
        invokeEnsureLoaded(loader);

        assertEquals(1, NativeLoaderLoadHook.calls());
        assertEquals(READY, loadState(loader));
    }

    @Test
    void sameThreadReentryFailsBeforeASecondLoadAndFailedStateDoesNotRetry() throws Exception {
        AtomicBoolean reenter = new AtomicBoolean(true);
        NativeLoaderLoadHook.action(anchor -> {
            if (reenter.compareAndSet(true, false)) {
                invokeEnsureLoadedUnchecked(anchor);
            }
        });
        Class<?> loader = defineWithTestLoadHook();

        InvocationTargetException first = invokeExpectingFailure(loader);
        assertTrue(first.getCause() instanceof UnsatisfiedLinkError, first.toString());
        assertTrue(first.getCause().getMessage().contains("registration completed"));
        assertEquals(1, NativeLoaderLoadHook.calls(), "recursive entry must not reach the load action twice");
        assertEquals(FAILED, loadState(loader));

        InvocationTargetException repeated = invokeExpectingFailure(loader);
        assertTrue(repeated.getCause() instanceof UnsatisfiedLinkError, repeated.toString());
        assertTrue(repeated.getCause().getMessage().contains("previously failed"));
        assertEquals(1, NativeLoaderLoadHook.calls(), "FAILED must never retry loading");
        assertEquals(FAILED, loadState(loader));
    }

    @Test
    void firstLoadFailureIsRethrownUnchangedAndNeverPublishesReady() throws Exception {
        AssertionError original = new AssertionError("synthetic load failure");
        NativeLoaderLoadHook.action(ignored -> {
            throw original;
        });
        Class<?> loader = defineWithTestLoadHook();

        InvocationTargetException failure = invokeExpectingFailure(loader);

        assertSame(original, failure.getCause());
        assertEquals(FAILED, loadState(loader));
        assertEquals(1, NativeLoaderLoadHook.calls());
    }

    @Test
    void unknownLoadStateFailsClosedWithoutTryingToLoad() throws Exception {
        Class<?> loader = defineWithTestLoadHook();
        setLoadState(loader, 99);

        InvocationTargetException failure = invokeExpectingFailure(loader);

        assertTrue(failure.getCause() instanceof UnsatisfiedLinkError, failure.toString());
        assertTrue(failure.getCause().getMessage().contains("invalid j2ll native library load state"));
        assertEquals(FAILED, loadState(loader));
        assertEquals(0, NativeLoaderLoadHook.calls());
    }

    @Test
    void concurrentFirstUseRunsOneLoadAndBothCallersObserveReady() throws Exception {
        CountDownLatch loadEntered = new CountDownLatch(1);
        CountDownLatch releaseLoad = new CountDownLatch(1);
        NativeLoaderLoadHook.action(ignored -> {
            loadEntered.countDown();
            await(releaseLoad);
        });
        Class<?> loader = defineWithTestLoadHook();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> invokeEnsureLoadedUnchecked(loader));
            assertTrue(loadEntered.await(5, TimeUnit.SECONDS), "first caller did not enter load action");
            Future<?> second = executor.submit(() -> invokeEnsureLoadedUnchecked(loader));

            assertThrows(TimeoutException.class, () -> second.get(100, TimeUnit.MILLISECONDS));
            assertEquals(1, NativeLoaderLoadHook.calls());
            assertEquals(LOADING, loadState(loader));

            releaseLoad.countDown();
            get(first);
            get(second);
            assertEquals(1, NativeLoaderLoadHook.calls());
            assertEquals(READY, loadState(loader));
        } finally {
            releaseLoad.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void loadStateIsIsolatedPerDefiningClassLoader() throws Exception {
        byte[] bytes = withTestLoadHook(generatedLoader());
        Class<?> first = new BytesClassLoader(getClass().getClassLoader()).define(bytes);
        Class<?> second = new BytesClassLoader(getClass().getClassLoader()).define(bytes);

        invokeEnsureLoaded(first);
        invokeEnsureLoaded(first);
        invokeEnsureLoaded(second);
        invokeEnsureLoaded(second);

        assertNotSame(first, second);
        assertNotSame(first.getClassLoader(), second.getClassLoader());
        assertEquals(READY, loadState(first));
        assertEquals(READY, loadState(second));
        assertEquals(2, NativeLoaderLoadHook.calls());
    }

    private byte[] generatedLoader() throws Exception {
        return new NativeLoaderClassGenerator().generate(
                RuntimeLoaderPlan.create("native0"),
                List.of());
    }

    private Class<?> defineWithTestLoadHook() throws Exception {
        return new BytesClassLoader(getClass().getClassLoader()).define(withTestLoadHook(generatedLoader()));
    }

    private byte[] withTestLoadHook(byte[] bytes) {
        ClassNode loader = read(bytes);
        MethodNode load = method(
                loader,
                "loadForCurrentTarget",
                "(Ljava/lang/Class;[Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;)V");
        load.instructions.clear();
        load.tryCatchBlocks.clear();
        load.localVariables = null;
        load.visibleLocalVariableAnnotations = null;
        load.invisibleLocalVariableAnnotations = null;
        load.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        load.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "xyz/melodysky/packaging/NativeLoaderLoadHook",
                "load",
                "(Ljava/lang/Class;)V",
                false));
        load.instructions.add(new InsnNode(Opcodes.RETURN));
        load.maxStack = 1;
        load.maxLocals = 4;

        ClassWriter writer = new ClassWriter(0);
        loader.accept(writer);
        return writer.toByteArray();
    }

    private InvocationTargetException invokeExpectingFailure(Class<?> loader) {
        return assertThrows(
                InvocationTargetException.class,
                () -> loader.getMethod("ensureLoaded").invoke(null));
    }

    private void invokeEnsureLoaded(Class<?> loader) throws Exception {
        loader.getMethod("ensureLoaded").invoke(null);
    }

    private static void invokeEnsureLoadedUnchecked(Class<?> loader) {
        try {
            loader.getMethod("ensureLoaded").invoke(null);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new AssertionError(cause);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static int loadState(Class<?> loader) {
        try {
            var field = loader.getDeclaredField("loadState");
            field.setAccessible(true);
            return field.getInt(null);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void setLoadState(Class<?> loader, int state) {
        try {
            var field = loader.getDeclaredField("loadState");
            field.setAccessible(true);
            field.setInt(null, state);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for loader test latch");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static void get(Future<?> future) throws Exception {
        try {
            future.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception checked) {
                throw checked;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new AssertionError(cause);
        }
    }

    private ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG);
        return node;
    }

    private MethodNode method(ClassNode node, String name, String descriptor) {
        return node.methods.stream()
                .filter(candidate -> candidate.name.equals(name) && candidate.desc.equals(descriptor))
                .findFirst()
                .orElseThrow();
    }

    private FieldNode field(ClassNode node, String name, String descriptor) {
        return node.fields.stream()
                .filter(candidate -> candidate.name.equals(name) && candidate.desc.equals(descriptor))
                .findFirst()
                .orElse(null);
    }

    private int stateWriteAfterConstant(MethodNode method, int value, int fromIndex) {
        AbstractInsnNode[] instructions = method.instructions.toArray();
        for (int index = fromIndex; index < instructions.length - 1; index++) {
            if (integerConstant(instructions[index]) == value
                    && instructions[index + 1] instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTSTATIC
                    && field.name.equals("loadState")
                    && field.desc.equals("I")) {
                return index + 1;
            }
        }
        throw new AssertionError("missing loadState write for value " + value);
    }

    private int instructionIndex(MethodNode method, Predicate<AbstractInsnNode> predicate) {
        AbstractInsnNode[] instructions = method.instructions.toArray();
        for (int index = 0; index < instructions.length; index++) {
            if (predicate.test(instructions[index])) {
                return index;
            }
        }
        throw new AssertionError("instruction not found");
    }

    private boolean hasRethrowAfter(MethodNode method, int fromIndex) {
        AbstractInsnNode[] instructions = method.instructions.toArray();
        for (int index = fromIndex + 1; index < instructions.length; index++) {
            if (instructions[index].getOpcode() == Opcodes.ATHROW) {
                return true;
            }
        }
        return false;
    }

    private int integerConstant(AbstractInsnNode instruction) {
        return switch (instruction.getOpcode()) {
            case Opcodes.ICONST_M1 -> -1;
            case Opcodes.ICONST_0 -> 0;
            case Opcodes.ICONST_1 -> 1;
            case Opcodes.ICONST_2 -> 2;
            case Opcodes.ICONST_3 -> 3;
            case Opcodes.ICONST_4 -> 4;
            case Opcodes.ICONST_5 -> 5;
            default -> Integer.MIN_VALUE;
        };
    }

    private static final class BytesClassLoader extends ClassLoader {
        private BytesClassLoader(ClassLoader parent) {
            super(parent);
        }

        private Class<?> define(byte[] bytes) {
            return defineClass(null, bytes, 0, bytes.length);
        }
    }
}
