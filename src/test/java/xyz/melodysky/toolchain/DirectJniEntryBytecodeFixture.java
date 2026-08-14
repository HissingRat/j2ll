package xyz.melodysky.toolchain;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** Bytecode shapes shared by the JNI proxy source and planner tests. */
final class DirectJniEntryBytecodeFixture implements Opcodes {
    static final String OWNER = "pkg/DirectEntryShapes";

    private DirectJniEntryBytecodeFixture() {}

    static byte[] eligibleClass() {
        ClassWriter writer = new ClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, OWNER, null,
                "java/lang/Object", null);
        defaultConstructor(writer);

        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "staticVoid",
                "()V",
                null,
                null);
        method.visitCode();
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        emitIntAdd(writer, ACC_PUBLIC | ACC_STATIC, "staticInt", 0, 7);
        emitLongXor(writer, ACC_PUBLIC | ACC_STATIC, "staticLong", 0);
        emitFloatNeg(writer, ACC_PUBLIC | ACC_STATIC, "staticFloat", 0);
        emitDoubleAdd(writer, ACC_PUBLIC | ACC_STATIC, "staticDouble", 0, 0.5d);
        emitIntAdd(writer, ACC_PUBLIC, "instanceInt", 1, 11);
        emitDoubleAdd(writer, ACC_PUBLIC, "instanceDouble", 1, 2.0d);

        writer.visitEnd();
        return writer.toByteArray();
    }

    static byte[] ineligibleClass() {
        ClassWriter writer = new ClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, OWNER, null,
                "java/lang/Object", null);
        writer.visitField(
                        ACC_PRIVATE | ACC_STATIC,
                        "state",
                        "I",
                        null,
                        null)
                .visitEnd();
        defaultConstructor(writer);
        emitNarrowIdentity(writer, "narrowBoolean", "(Z)Z");
        emitNarrowIdentity(writer, "narrowByte", "(B)B");
        emitNarrowIdentity(writer, "narrowChar", "(C)C");
        emitNarrowIdentity(writer, "narrowShort", "(S)S");

        MethodVisitor reference = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "referenceIdentity",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                null,
                null);
        reference.visitCode();
        reference.visitVarInsn(ALOAD, 0);
        reference.visitInsn(ARETURN);
        reference.visitMaxs(0, 0);
        reference.visitEnd();

        MethodVisitor field = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "readField",
                "()I",
                null,
                null);
        field.visitCode();
        field.visitFieldInsn(GETSTATIC, OWNER, "state", "I");
        field.visitInsn(IRETURN);
        field.visitMaxs(0, 0);
        field.visitEnd();

        MethodVisitor divide = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "divide",
                "(II)I",
                null,
                null);
        divide.visitCode();
        divide.visitVarInsn(ILOAD, 0);
        divide.visitVarInsn(ILOAD, 1);
        divide.visitInsn(IDIV);
        divide.visitInsn(IRETURN);
        divide.visitMaxs(0, 0);
        divide.visitEnd();

        MethodVisitor throwing = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "alwaysThrow",
                "(Ljava/lang/Throwable;)V",
                null,
                null);
        throwing.visitCode();
        throwing.visitVarInsn(ALOAD, 0);
        throwing.visitInsn(ATHROW);
        throwing.visitMaxs(0, 0);
        throwing.visitEnd();

        emitIntAdd(writer, ACC_PUBLIC | ACC_STATIC, "callee", 0, 3);
        MethodVisitor caller = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "caller",
                "(I)I",
                null,
                null);
        caller.visitCode();
        caller.visitVarInsn(ILOAD, 0);
        caller.visitMethodInsn(
                INVOKESTATIC,
                OWNER,
                "callee",
                "(I)I",
                false);
        caller.visitInsn(IRETURN);
        caller.visitMaxs(0, 0);
        caller.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void defaultConstructor(ClassWriter writer) {
        MethodVisitor constructor = writer.visitMethod(
                ACC_PUBLIC,
                "<init>",
                "()V",
                null,
                null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(
                INVOKESPECIAL,
                "java/lang/Object",
                "<init>",
                "()V",
                false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
    }

    private static void emitIntAdd(
            ClassWriter writer,
            int access,
            String name,
            int slot,
            int value) {
        MethodVisitor method = writer.visitMethod(
                access,
                name,
                "(I)I",
                null,
                null);
        method.visitCode();
        method.visitVarInsn(ILOAD, slot);
        method.visitLdcInsn(value);
        method.visitInsn(IADD);
        method.visitInsn(IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void emitLongXor(
            ClassWriter writer,
            int access,
            String name,
            int slot) {
        MethodVisitor method = writer.visitMethod(
                access,
                name,
                "(J)J",
                null,
                null);
        method.visitCode();
        method.visitVarInsn(LLOAD, slot);
        method.visitLdcInsn(0x1020304050607080L);
        method.visitInsn(LXOR);
        method.visitInsn(LRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void emitFloatNeg(
            ClassWriter writer,
            int access,
            String name,
            int slot) {
        MethodVisitor method = writer.visitMethod(
                access,
                name,
                "(F)F",
                null,
                null);
        method.visitCode();
        method.visitVarInsn(FLOAD, slot);
        method.visitInsn(FNEG);
        method.visitInsn(FRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void emitDoubleAdd(
            ClassWriter writer,
            int access,
            String name,
            int slot,
            double value) {
        MethodVisitor method = writer.visitMethod(
                access,
                name,
                "(D)D",
                null,
                null);
        method.visitCode();
        method.visitVarInsn(DLOAD, slot);
        method.visitLdcInsn(value);
        method.visitInsn(DADD);
        method.visitInsn(DRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void emitNarrowIdentity(
            ClassWriter writer,
            String name,
            String descriptor) {
        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                name,
                descriptor,
                null,
                null);
        method.visitCode();
        method.visitVarInsn(ILOAD, 0);
        method.visitInsn(Type.getReturnType(descriptor).getOpcode(IRETURN));
        method.visitMaxs(0, 0);
        method.visitEnd();
    }
}
