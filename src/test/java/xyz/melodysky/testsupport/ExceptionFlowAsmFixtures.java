package xyz.melodysky.testsupport;

import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.ACC_SYNCHRONIZED;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ASTORE;
import static org.objectweb.asm.Opcodes.ATHROW;
import static org.objectweb.asm.Opcodes.ICONST_1;
import static org.objectweb.asm.Opcodes.ICONST_2;
import static org.objectweb.asm.Opcodes.IRETURN;
import static org.objectweb.asm.Opcodes.V17;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/** Focused bytecode shapes for JVM exception ordering and cleanup tests. */
public final class ExceptionFlowAsmFixtures {
    private ExceptionFlowAsmFixtures() {}

    public static byte[] classWithHandlerTableOrderOppositeToLayout(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);

        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC,
                "ordered",
                "(Ljava/lang/RuntimeException;)I",
                null,
                null);
        Label start = new Label();
        Label end = new Label();
        Label broadHandlerFirstInLayout = new Label();
        Label specificHandlerLastInLayout = new Label();
        method.visitTryCatchBlock(
                start,
                end,
                specificHandlerLastInLayout,
                "java/lang/IllegalArgumentException");
        method.visitTryCatchBlock(
                start,
                end,
                broadHandlerFirstInLayout,
                "java/lang/RuntimeException");
        method.visitCode();
        method.visitLabel(start);
        method.visitVarInsn(ALOAD, 1);
        method.visitInsn(ATHROW);
        method.visitLabel(end);
        method.visitLabel(broadHandlerFirstInLayout);
        method.visitVarInsn(ASTORE, 2);
        method.visitInsn(ICONST_2);
        method.visitInsn(IRETURN);
        method.visitLabel(specificHandlerLastInLayout);
        method.visitVarInsn(ASTORE, 2);
        method.visitInsn(ICONST_1);
        method.visitInsn(IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithSynchronizedCaughtExplicitThrow(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);

        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_SYNCHRONIZED,
                "caughtThrow",
                "(Ljava/lang/RuntimeException;)I",
                null,
                null);
        Label start = new Label();
        Label end = new Label();
        Label handler = new Label();
        method.visitTryCatchBlock(start, end, handler, "java/lang/RuntimeException");
        method.visitCode();
        method.visitLabel(start);
        method.visitVarInsn(ALOAD, 1);
        method.visitInsn(ATHROW);
        method.visitLabel(end);
        method.visitLabel(handler);
        method.visitVarInsn(ASTORE, 2);
        method.visitInsn(ICONST_1);
        method.visitInsn(IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }
}
