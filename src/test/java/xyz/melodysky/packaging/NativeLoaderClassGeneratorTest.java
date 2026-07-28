package xyz.melodysky.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import xyz.melodysky.toolchain.HostPlatform;
import xyz.melodysky.toolchain.NativeLibraryArtifact;
import xyz.melodysky.toolchain.TargetTriple;

class NativeLoaderClassGeneratorTest {
    @Test
    void emitsOneJava17LoaderAtEmbeddedLibraryDirectory() throws Exception {
        RuntimeLoaderPlan plan = RuntimeLoaderPlan.create("xyz/Melody/natives");
        byte[] bytes = new NativeLoaderClassGenerator().generate(
                plan,
                List.of(artifact(TargetTriple.WINDOWS_X64, "xyz/Melody/natives/x64-windows.dll")));
        ClassNode loader = read(bytes);

        assertEquals("xyz/Melody/natives/Loader", loader.name);
        assertEquals("java/lang/Object", loader.superName);
        assertEquals(Opcodes.V17, loader.version);
        assertNotNull(method(loader, "ensureLoaded", "()V"));
        assertFalse(hasMethod(loader, "defineHiddenFallback", "(Ljava/lang/Class;[B)Ljava/lang/Class;"));
        assertFalse(hasMethod(
                loader,
                LoaderClassValueSidecarInjector.ACCESSOR_NAME,
                LoaderClassValueSidecarInjector.ACCESSOR_DESCRIPTOR));
        String constants = new String(bytes, StandardCharsets.ISO_8859_1);
        assertFalse(constants.contains("J2llNativeLoaderSupport"), constants);
        assertFalse(constants.contains("J2llFallbackSupport"), constants);
        assertFalse(constants.contains("LoaderTemplate"), constants);
        assertFalse(constants.contains("java/lang/invoke/MethodHandles"), constants);

        Class<?> defined = new BytesClassLoader(getClass().getClassLoader()).define(bytes);
        assertEquals("xyz.Melody.natives.Loader", defined.getName());
    }

    @Test
    void referenceSidecarDoesNotAddABytecodeDefinitionBridge() throws Exception {
        RuntimeLoaderPlan plan = RuntimeLoaderPlan.create("native0", 2);
        byte[] bytes = new NativeLoaderClassGenerator().generate(plan, List.of());
        ClassNode loader = read(bytes);

        assertFalse(hasMethod(loader, "defineHiddenFallback", "(Ljava/lang/Class;[B)Ljava/lang/Class;"));
        assertTrue(hasMethod(
                loader,
                LoaderClassValueSidecarInjector.ACCESSOR_NAME,
                LoaderClassValueSidecarInjector.ACCESSOR_DESCRIPTOR));
        assertEquals("java/lang/ClassValue", loader.superName);
        String constants = new String(bytes, StandardCharsets.ISO_8859_1);
        assertFalse(constants.contains("java/lang/invoke/MethodHandles"), constants);
        assertFalse(constants.contains("J2llFallbackSupport"), constants);
    }

    @Test
    void classValueSidecarCachesPerDefiningClassWithoutExtraGeneratedClass() throws Exception {
        RuntimeLoaderPlan plan = RuntimeLoaderPlan.create("native0", 3);
        byte[] bytes = new NativeLoaderClassGenerator().generate(plan, List.of());
        ClassNode node = read(bytes);

        assertEquals("java/lang/ClassValue", node.superName);
        assertTrue(node.innerClasses.stream().noneMatch(inner ->
                inner.name.startsWith("native0/Loader$")));
        Class<?> loader = new BytesClassLoader(getClass().getClassLoader()).define(bytes);
        var accessor = loader.getDeclaredMethod(
                LoaderClassValueSidecarInjector.ACCESSOR_NAME,
                Class.class);
        accessor.setAccessible(true);
        Object[] first = (Object[]) accessor.invoke(null, String.class);
        Object[] repeated = (Object[]) accessor.invoke(null, String.class);
        Object[] other = (Object[]) accessor.invoke(null, Integer.class);

        assertEquals(3, first.length);
        assertSame(first, repeated);
        assertNotSame(first, other);
        first[1] = "retained-by-jvm-sidecar";
        assertEquals("retained-by-jvm-sidecar", repeated[1]);
    }

    @Test
    void missingNativeResourceFailsBeforeSystemLoad() throws Exception {
        TargetTriple host = HostPlatform.detect().orElseThrow().target();
        RuntimeLoaderPlan plan = RuntimeLoaderPlan.create("native0");
        byte[] bytes = new NativeLoaderClassGenerator().generate(
                plan,
                List.of(artifact(host, "native0/missing-library" + extension(host))));
        Class<?> loader = new BytesClassLoader(getClass().getClassLoader()).define(bytes);

        InvocationTargetException error = assertThrows(
                InvocationTargetException.class,
                () -> loader.getMethod("ensureLoaded").invoke(null));
        assertTrue(error.getCause() instanceof UnsatisfiedLinkError, error.toString());
        assertTrue(error.getCause().getMessage().contains("resource not found"), error.getCause().getMessage());
    }

    private NativeLibraryArtifact artifact(TargetTriple target, String jarPath) {
        return new NativeLibraryArtifact(
                target,
                Path.of(target.libraryFileName()),
                Path.of("loader.c"),
                jarPath,
                "0".repeat(64),
                List.of("JNI_OnLoad"));
    }

    private String extension(TargetTriple target) {
        return "." + target.libraryExtension();
    }

    private ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG);
        return node;
    }

    private boolean hasMethod(ClassNode node, String name, String descriptor) {
        return node.methods.stream().anyMatch(method ->
                method.name.equals(name) && method.desc.equals(descriptor));
    }

    private MethodNode method(ClassNode node, String name, String descriptor) {
        return node.methods.stream()
                .filter(candidate -> candidate.name.equals(name) && candidate.desc.equals(descriptor))
                .findFirst()
                .orElse(null);
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
