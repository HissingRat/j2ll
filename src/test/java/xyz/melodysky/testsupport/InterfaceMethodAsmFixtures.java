package xyz.melodysky.testsupport;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class InterfaceMethodAsmFixtures implements Opcodes {
    private InterfaceMethodAsmFixtures() {
    }

    public static byte[] interfaceWithDefaultStaticAndPrivate(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(
                V17,
                ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE,
                internalName,
                null,
                "java/lang/Object",
                null);
        constantMethod(writer, ACC_PUBLIC, "defaultAnswer", 3);
        constantMethod(writer, ACC_PUBLIC | ACC_STATIC, "staticAnswer", 5);
        constantMethod(writer, ACC_PRIVATE, "privateAnswer", 7);
        MethodVisitor callPrivate = writer.visitMethod(
                ACC_PUBLIC,
                "callPrivate",
                "()I",
                null,
                null);
        callPrivate.visitCode();
        callPrivate.visitVarInsn(ALOAD, 0);
        callPrivate.visitMethodInsn(
                INVOKEINTERFACE,
                internalName,
                "privateAnswer",
                "()I",
                true);
        callPrivate.visitInsn(IRETURN);
        callPrivate.visitMaxs(0, 0);
        callPrivate.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void constantMethod(
            ClassWriter writer,
            int access,
            String name,
            int value) {
        MethodVisitor method = writer.visitMethod(access, name, "()I", null, null);
        method.visitCode();
        method.visitIntInsn(BIPUSH, value);
        method.visitInsn(IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }
}
