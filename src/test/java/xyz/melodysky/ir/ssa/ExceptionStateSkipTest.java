package xyz.melodysky.ir.ssa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import xyz.melodysky.frontend.cfg.MethodCfgBuilder;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassFileEntry;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.validate.IrMethodValidator;
import xyz.melodysky.pipeline.LoweringStatus;

class ExceptionStateSkipTest implements Opcodes {
    @Test
    void handlerLiveLocalStateIsCarriedByExceptionEdgeArguments() {
        String owner = "pkg/HandlerLocalState";
        var parsedClass = new AsmClassParser()
                .parse(new ClassFileEntry(owner + ".class", fixture(owner), "fixture"))
                .artifact()
                .orElseThrow();
        var method = parsedClass.methods().stream()
                .filter(candidate -> candidate.name().equals("guarded"))
                .findFirst()
                .orElseThrow();

        var cfg = new MethodCfgBuilder().build(method).artifact().orElseThrow();
        var result = new BytecodeToSsaLowerer().lower(cfg);
        var artifact = result.artifact().orElseThrow();

        assertEquals(LoweringStatus.NATIVE_LOWERED, artifact.status());
        var irMethod = artifact.irMethod().orElseThrow();
        assertTrue(new IrMethodValidator().validate(irMethod).isEmpty());
        assertTrue(result.diagnostics().isEmpty());
        assertTrue(irMethod.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .flatMap(instruction -> instruction.exceptionSites().stream())
                .anyMatch(site -> site.exceptionValue().isPresent()
                        && site.handlers().stream().anyMatch(edge ->
                                edge.arguments().size() >= 2
                                        && edge.arguments().get(0)
                                                .equals(site.exceptionValue().orElseThrow())
                                        && edge.arguments().get(1).type()
                                                == IrType.REFERENCE)));
    }

    private byte[] fixture(String owner) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, owner, null, "java/lang/Object", null);

        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        MethodVisitor method = writer.visitMethod(ACC_PUBLIC, "guarded", "()V", null, null);
        Label start = new Label();
        Label end = new Label();
        Label handler = new Label();
        Label join = new Label();
        method.visitTryCatchBlock(start, end, handler, "java/lang/RuntimeException");
        method.visitCode();
        method.visitTypeInsn(NEW, "java/lang/Object");
        method.visitInsn(DUP);
        method.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        method.visitVarInsn(ASTORE, 1);
        method.visitLabel(start);
        method.visitMethodInsn(INVOKESTATIC, "pkg/MayThrow", "run", "()V", false);
        method.visitLabel(end);
        method.visitJumpInsn(GOTO, join);
        method.visitLabel(handler);
        method.visitVarInsn(ASTORE, 2);
        method.visitJumpInsn(GOTO, join);
        method.visitLabel(join);
        method.visitVarInsn(ALOAD, 1);
        method.visitInsn(POP);
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }
}
