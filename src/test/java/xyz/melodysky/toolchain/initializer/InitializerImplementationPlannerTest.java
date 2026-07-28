package xyz.melodysky.toolchain.initializer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        return new BytecodeToSsaLowerer()
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
