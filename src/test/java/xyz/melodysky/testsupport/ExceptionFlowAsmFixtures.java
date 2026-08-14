package xyz.melodysky.testsupport;

import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.ACC_SYNCHRONIZED;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ASTORE;
import static org.objectweb.asm.Opcodes.ATHROW;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.DUP;
import static org.objectweb.asm.Opcodes.GETSTATIC;
import static org.objectweb.asm.Opcodes.GOTO;
import static org.objectweb.asm.Opcodes.IADD;
import static org.objectweb.asm.Opcodes.ICONST_0;
import static org.objectweb.asm.Opcodes.ICONST_1;
import static org.objectweb.asm.Opcodes.ICONST_2;
import static org.objectweb.asm.Opcodes.ICONST_3;
import static org.objectweb.asm.Opcodes.IDIV;
import static org.objectweb.asm.Opcodes.ILOAD;
import static org.objectweb.asm.Opcodes.IRETURN;
import static org.objectweb.asm.Opcodes.ISTORE;
import static org.objectweb.asm.Opcodes.MONITORENTER;
import static org.objectweb.asm.Opcodes.MONITOREXIT;
import static org.objectweb.asm.Opcodes.PUTSTATIC;
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

    public static byte[] classWithSelfProtectedSynchronizedCleanup(
            String internalName) {
        ClassWriter writer = new ClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(
                V17,
                ACC_PUBLIC | ACC_SUPER,
                internalName,
                null,
                "java/lang/Object",
                null);
        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC,
                "cleanup",
                "(Ljava/lang/Object;)I",
                null,
                null);
        Label bodyStart = new Label();
        Label bodyEnd = new Label();
        Label cleanup = new Label();
        Label cleanupEnd = new Label();
        Label typed = new Label();
        Label broad = new Label();
        method.visitTryCatchBlock(bodyStart, bodyEnd, cleanup, null);
        method.visitTryCatchBlock(cleanup, cleanupEnd, cleanup, null);
        method.visitTryCatchBlock(
                bodyStart,
                cleanupEnd,
                typed,
                "java/lang/ArithmeticException");
        method.visitTryCatchBlock(bodyStart, cleanupEnd, broad, null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 1);
        method.visitInsn(DUP);
        method.visitVarInsn(ASTORE, 2);
        method.visitInsn(MONITORENTER);
        method.visitLabel(bodyStart);
        method.visitInsn(ICONST_1);
        method.visitInsn(ICONST_2);
        method.visitInsn(IDIV);
        method.visitVarInsn(ALOAD, 2);
        method.visitInsn(MONITOREXIT);
        method.visitLabel(bodyEnd);
        method.visitInsn(IRETURN);
        method.visitLabel(cleanup);
        method.visitVarInsn(ASTORE, 3);
        method.visitVarInsn(ALOAD, 2);
        method.visitInsn(MONITOREXIT);
        method.visitLabel(cleanupEnd);
        method.visitVarInsn(ALOAD, 3);
        method.visitInsn(ATHROW);
        method.visitLabel(typed);
        method.visitVarInsn(ASTORE, 3);
        method.visitInsn(ICONST_2);
        method.visitInsn(IRETURN);
        method.visitLabel(broad);
        method.visitVarInsn(ASTORE, 3);
        method.visitInsn(ICONST_1);
        method.visitInsn(IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    public static byte[] classWithRecoveringSynchronizedCleanup(
            String internalName) {
        ClassWriter writer = new ClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(
                V17,
                ACC_PUBLIC | ACC_SUPER,
                internalName,
                null,
                "java/lang/Object",
                null);
        writer.visitField(
                        ACC_PRIVATE | ACC_STATIC,
                        "LOCK",
                        "Ljava/lang/Object;",
                        null,
                        null)
                .visitEnd();
        writer.visitField(
                        ACC_PRIVATE | ACC_STATIC,
                        "counter",
                        "I",
                        null,
                        null)
                .visitEnd();

        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "recover",
                "()Ljava/lang/String;",
                null,
                null);
        Label outerStart = new Label();
        Label bodyStart = new Label();
        Label bodyEnd = new Label();
        Label cleanup = new Label();
        Label cleanupEnd = new Label();
        Label outerEnd = new Label();
        Label typed = new Label();
        Label normal = new Label();
        method.visitTryCatchBlock(bodyStart, bodyEnd, cleanup, null);
        method.visitTryCatchBlock(cleanup, cleanupEnd, cleanup, null);
        method.visitTryCatchBlock(
                outerStart,
                outerEnd,
                typed,
                "java/lang/ArithmeticException");
        method.visitCode();
        method.visitLabel(outerStart);
        method.visitFieldInsn(
                GETSTATIC,
                internalName,
                "LOCK",
                "Ljava/lang/Object;");
        method.visitInsn(DUP);
        method.visitVarInsn(ASTORE, 0);
        method.visitInsn(MONITORENTER);
        method.visitLabel(bodyStart);
        method.visitFieldInsn(GETSTATIC, internalName, "counter", "I");
        method.visitInsn(ICONST_3);
        method.visitInsn(IADD);
        method.visitFieldInsn(PUTSTATIC, internalName, "counter", "I");
        method.visitInsn(ICONST_0);
        method.visitVarInsn(ISTORE, 1);
        method.visitFieldInsn(GETSTATIC, internalName, "counter", "I");
        method.visitInsn(ICONST_1);
        method.visitVarInsn(ILOAD, 1);
        method.visitInsn(IDIV);
        method.visitInsn(IADD);
        method.visitFieldInsn(PUTSTATIC, internalName, "counter", "I");
        method.visitVarInsn(ALOAD, 0);
        method.visitInsn(MONITOREXIT);
        method.visitLabel(bodyEnd);
        method.visitJumpInsn(GOTO, outerEnd);
        method.visitLabel(cleanup);
        method.visitVarInsn(ASTORE, 2);
        method.visitVarInsn(ALOAD, 0);
        method.visitInsn(MONITOREXIT);
        method.visitLabel(cleanupEnd);
        method.visitVarInsn(ALOAD, 2);
        method.visitInsn(ATHROW);
        method.visitLabel(outerEnd);
        method.visitJumpInsn(GOTO, normal);
        method.visitLabel(typed);
        method.visitVarInsn(ASTORE, 0);
        method.visitLdcInsn("monitor");
        method.visitInsn(ARETURN);
        method.visitLabel(normal);
        method.visitLdcInsn("none");
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

}
