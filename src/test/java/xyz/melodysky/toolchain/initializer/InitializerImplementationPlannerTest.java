package xyz.melodysky.toolchain.initializer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import xyz.melodysky.frontend.cfg.MethodCfgBuilder;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassFileEntry;
import xyz.melodysky.frontend.classfile.ParsedClass;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.ssa.BytecodeToSsaLowerer;
import xyz.melodysky.packaging.MethodRewriteDecision;
import xyz.melodysky.packaging.MethodRewritePlanner;
import xyz.melodysky.packaging.NativeRegistrationPlanner;
import xyz.melodysky.runtime.RuntimeTokenMapper;
import xyz.melodysky.testsupport.CapturedTimerTaskFixture;
import xyz.melodysky.toolchain.NativeImplementationPath;
import xyz.melodysky.toolchain.NativeImplementationPlanner;

class InitializerImplementationPlannerTest implements Opcodes {
    private final InitializerImplementationPlanner planner =
            new InitializerImplementationPlanner();

    @Test
    void splitsAfterTheActualParameterizedSuperInvocation() {
        ParsedClass parsedClass = parsed("pkg/Child", childClass());
        MethodRewriteDecision decision = decision(parsedClass, "<init>");
        InitializerImplementationPlan plan = planner.plan(
                        decision,
                        irMethod(decision.method()))
                .orElseThrow();

        ConstructorPrefixPlan prefix = plan.constructorPrefix().orElseThrow();
        assertEquals(InitializerImplementationKind.CONSTRUCTOR, plan.kind());
        assertEquals(3, prefix.initializationOpcodeIndex());
        assertEquals("pkg/Base", prefix.targetOwner());
        assertEquals("(Ljava/lang/String;I)V", prefix.targetDescriptor());
        assertTrue(plan.nativeBody().blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .noneMatch(instruction -> instruction.opcode() == IrOpcode.CALL_SPECIAL
                        && instruction.symbol()
                                .map(symbol -> symbol.equals(
                                        "pkg/Base#<init>!(Ljava/lang/String;I)V"))
                                .orElse(false)));
        assertTrue(plan.nativeBody().blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(instruction -> instruction.opcode() == IrOpcode.PUT_FIELD));
    }

    @Test
    void capturedFieldAndRequireNonNullPrefixProducesTimerTaskNativeBodyHelper() {
        ParsedClass parsedClass = parsed(
                CapturedTimerTaskFixture.OWNER,
                CapturedTimerTaskFixture.classBytes());
        MethodRewriteDecision decision = decision(parsedClass, "<init>");
        RuntimeTokenMapper runtimeTokens = RuntimeTokenMapper.fromBytes(
                "captured-timer-task-build-token"
                        .getBytes(StandardCharsets.UTF_8));
        IrMethod source = irMethod(decision.method(), runtimeTokens);
        InitializerImplementationPlan initializerPlan =
                new InitializerImplementationPlanner(runtimeTokens)
                        .plan(decision, source)
                        .orElseThrow();

        ConstructorPrefixPlan prefix =
                initializerPlan.constructorPrefix().orElseThrow();
        assertEquals(9, prefix.initializationOpcodeIndex());
        assertEquals("java/util/TimerTask", prefix.targetOwner());
        assertEquals("()V", prefix.targetDescriptor());

        List<xyz.melodysky.ir.model.IrInstruction> sourceInstructions =
                source.blocks().stream()
                        .flatMap(block -> block.instructions().stream())
                        .toList();
        assertTrue(sourceInstructions.stream()
                .anyMatch(instruction ->
                        instruction.opcode() == IrOpcode.PUT_FIELD));
        assertTrue(sourceInstructions.stream()
                .anyMatch(instruction ->
                        instruction.opcode() == IrOpcode.CALL_RUNTIME_HELPER
                                && instruction.symbol()
                                        .map("j2ll_rt_objects_require_non_null"::equals)
                                        .orElse(false)));
        assertTrue(sourceInstructions.stream()
                .anyMatch(instruction ->
                        instruction.opcode() == IrOpcode.CALL_SPECIAL
                                && instruction.symbol()
                                        .map("java/util/TimerTask#<init>!()V"::equals)
                                        .orElse(false)));
        assertTrue(initializerPlan.nativeBody().blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .findAny()
                .isEmpty());

        var nativePlan = new NativeImplementationPlanner().plan(
                new NativeRegistrationPlanner().plan(List.of(decision)),
                List.of(decision),
                Map.of(decision.method().methodKey(), initializerPlan.nativeBody()),
                Set.of(decision.method().methodKey()),
                Set.of(),
                Map.of(decision.method().methodKey(), initializerPlan));

        assertTrue(nativePlan.unavailableReasonCodes().isEmpty());
        assertEquals(1, nativePlan.implementations().size());
        var implementation = nativePlan.implementations().get(0);
        assertEquals(NativeImplementationPath.LLVM_NATIVE_PATH, implementation.path());
        assertEquals("LLVM_CONSTRUCTOR_SPLIT_BODY_IR", implementation.reasonCode());
        assertEquals(
                CapturedTimerTaskFixture.NATIVE_BODY_DESCRIPTOR,
                implementation.entry().descriptor());
        assertEquals(
                decision.generatedHelperName().orElseThrow(),
                implementation.entry().methodName());
        assertTrue(implementation.entry().methodName().matches("[a-p]{32}"));
        assertEquals(
                initializerPlan,
                implementation.initializerPlan().orElseThrow());
    }

    @Test
    void classInitializerKeepsItsCompleteLlvmBody() {
        ParsedClass parsedClass = parsed("pkg/StaticState", classInitializerClass());
        MethodRewriteDecision decision = decision(parsedClass, "<clinit>");
        IrMethod source = irMethod(decision.method());
        InitializerImplementationPlan plan = planner.plan(decision, source).orElseThrow();

        assertEquals(InitializerImplementationKind.CLASS_INITIALIZER, plan.kind());
        assertTrue(plan.constructorPrefix().isEmpty());
        assertEquals(source, plan.nativeBody());
        assertTrue(plan.nativeBody().blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(instruction -> instruction.opcode() == IrOpcode.NEW_OBJECT));
    }

    @Test
    void constructorWithExceptionTableFailsClosed() {
        ParsedClass parsedClass = parsed("pkg/CatchingChild", catchingConstructorClass());
        MethodRewriteDecision decision = decision(parsedClass, "<init>");

        assertFalse(planner.plan(decision, irMethod(decision.method())).isPresent());
    }

    private MethodRewriteDecision decision(ParsedClass parsedClass, String name) {
        return new MethodRewritePlanner().planClass(parsedClass).stream()
                .filter(candidate -> candidate.method().name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private IrMethod irMethod(ParsedMethod method) {
        return irMethod(method, RuntimeTokenMapper.compatibility());
    }

    private IrMethod irMethod(
            ParsedMethod method,
            RuntimeTokenMapper runtimeTokens) {
        return new BytecodeToSsaLowerer(runtimeTokens)
                .lower(new MethodCfgBuilder().build(method).artifact().orElseThrow())
                .artifact()
                .orElseThrow()
                .irMethod()
                .orElseThrow();
    }

    private ParsedClass parsed(String internalName, byte[] bytes) {
        return new AsmClassParser()
                .parse(new ClassFileEntry(internalName + ".class", bytes, "fixture"))
                .artifact()
                .orElseThrow();
    }

    private byte[] childClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/Child", null, "pkg/Base", null);
        writer.visitField(ACC_PRIVATE, "value", "I", null, null).visitEnd();
        MethodVisitor constructor = writer.visitMethod(
                ACC_PUBLIC,
                "<init>",
                "(Ljava/lang/String;I)V",
                null,
                null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitVarInsn(ALOAD, 1);
        constructor.visitVarInsn(ILOAD, 2);
        constructor.visitMethodInsn(
                INVOKESPECIAL,
                "pkg/Base",
                "<init>",
                "(Ljava/lang/String;I)V",
                false);
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitVarInsn(ILOAD, 2);
        constructor.visitInsn(ICONST_2);
        constructor.visitInsn(IMUL);
        constructor.visitFieldInsn(PUTFIELD, "pkg/Child", "value", "I");
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] classInitializerClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/StaticState", null, "java/lang/Object", null);
        writer.visitField(ACC_STATIC, "value", "Ljava/lang/Object;", null, null).visitEnd();
        MethodVisitor initializer = writer.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        initializer.visitCode();
        initializer.visitTypeInsn(NEW, "java/lang/Object");
        initializer.visitInsn(DUP);
        initializer.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        initializer.visitFieldInsn(PUTSTATIC, "pkg/StaticState", "value", "Ljava/lang/Object;");
        initializer.visitInsn(RETURN);
        initializer.visitMaxs(0, 0);
        initializer.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] catchingConstructorClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/CatchingChild", null, "java/lang/Object", null);
        org.objectweb.asm.Label start = new org.objectweb.asm.Label();
        org.objectweb.asm.Label end = new org.objectweb.asm.Label();
        org.objectweb.asm.Label handler = new org.objectweb.asm.Label();
        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitTryCatchBlock(start, end, handler, "java/lang/RuntimeException");
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitLabel(start);
        constructor.visitInsn(RETURN);
        constructor.visitLabel(end);
        constructor.visitLabel(handler);
        constructor.visitInsn(ATHROW);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
