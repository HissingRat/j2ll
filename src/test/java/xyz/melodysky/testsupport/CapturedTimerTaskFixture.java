package xyz.melodysky.testsupport;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Javac-shaped anonymous {@link java.util.TimerTask} constructor whose
 * verifier prefix includes a captured-field store and
 * {@link java.util.Objects#requireNonNull(Object)} before the super call.
 */
public final class CapturedTimerTaskFixture implements Opcodes {
    public static final String OWNER = "pkg/CapturedTimerTask";
    public static final String DESCRIPTOR =
            "(Lpkg/Outer;Lpkg/State;)V";
    public static final String NATIVE_BODY_DESCRIPTOR =
            "(Lpkg/CapturedTimerTask;Lpkg/Outer;Lpkg/State;)V";

    private CapturedTimerTaskFixture() {}

    public static byte[] classBytes() {
        ClassWriter writer = new ClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(
                V17,
                ACC_FINAL | ACC_SUPER,
                OWNER,
                null,
                "java/util/TimerTask",
                null);
        writer.visitField(
                        ACC_FINAL | ACC_SYNTHETIC,
                        "captured",
                        "Lpkg/State;",
                        null,
                        null)
                .visitEnd();
        MethodVisitor constructor = writer.visitMethod(
                0,
                "<init>",
                DESCRIPTOR,
                null,
                null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitVarInsn(ALOAD, 2);
        constructor.visitFieldInsn(
                PUTFIELD,
                OWNER,
                "captured",
                "Lpkg/State;");
        constructor.visitVarInsn(ALOAD, 1);
        constructor.visitInsn(DUP);
        constructor.visitMethodInsn(
                INVOKESTATIC,
                "java/util/Objects",
                "requireNonNull",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                false);
        constructor.visitInsn(POP);
        constructor.visitInsn(POP);
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(
                INVOKESPECIAL,
                "java/util/TimerTask",
                "<init>",
                "()V",
                false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
