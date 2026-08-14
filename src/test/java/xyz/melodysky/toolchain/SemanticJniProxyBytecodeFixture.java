package xyz.melodysky.toolchain;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** Compact bytecode corpus for semantic-surface JNI proxy planning tests. */
final class SemanticJniProxyBytecodeFixture implements Opcodes {
    static final String OWNER = "pkg/SemanticProxyShapes";

    private SemanticJniProxyBytecodeFixture() {}

    static byte[] classBytes() {
        ClassWriter writer = new ClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, OWNER, null,
                "java/lang/Object", null);
        writer.visitField(ACC_PRIVATE | ACC_STATIC, "staticState", "I", null, null)
                .visitEnd();
        writer.visitField(ACC_PRIVATE, "instanceState", "I", null, null)
                .visitEnd();
        constructor(writer);
        classInitializer(writer);
        referenceIdentity(writer, ACC_PUBLIC | ACC_STATIC, "staticIdentity", 0);
        referenceIdentity(writer, ACC_PUBLIC, "instanceIdentity", 1);
        arrayIdentity(writer, "intArrayIdentity", "([I)[I");
        arrayIdentity(
                writer,
                "objectArrayIdentity",
                "([Ljava/lang/Object;)[Ljava/lang/Object;");
        allocateObject(writer);
        allocateBytes(writer);
        readStaticField(writer, ACC_PUBLIC | ACC_STATIC, "readStaticField");
        readInstanceField(writer);
        readStaticField(writer, ACC_PUBLIC, "readStaticFromInstance");
        integerOperation(writer, "divide", IDIV);
        integerOperation(writer, "remainder", IREM);
        callStringValueOf(writer);
        alwaysThrow(writer);
        synchronizedIdentity(writer);
        narrowIdentity(writer, "narrowBoolean", "(Z)Z");
        narrowIdentity(writer, "narrowByte", "(B)B");
        narrowIdentity(writer, "narrowChar", "(C)C");
        narrowIdentity(writer, "narrowShort", "(S)S");
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void constructor(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC, "<init>", "()V", null, null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitMethodInsn(
                INVOKESPECIAL,
                "java/lang/Object",
                "<init>",
                "()V",
                false);
        method.visitVarInsn(ALOAD, 0);
        method.visitIntInsn(BIPUSH, 29);
        method.visitFieldInsn(PUTFIELD, OWNER, "instanceState", "I");
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void classInitializer(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(
                ACC_STATIC, "<clinit>", "()V", null, null);
        method.visitCode();
        method.visitIntInsn(BIPUSH, 41);
        method.visitFieldInsn(PUTSTATIC, OWNER, "staticState", "I");
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void referenceIdentity(
            ClassWriter writer,
            int access,
            String name,
            int slot) {
        MethodVisitor method = writer.visitMethod(
                access,
                name,
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                null,
                null);
        method.visitCode();
        method.visitVarInsn(ALOAD, slot);
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void arrayIdentity(
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
        method.visitVarInsn(ALOAD, 0);
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void allocateObject(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "allocateObject",
                "()Ljava/lang/Object;",
                null,
                null);
        method.visitCode();
        method.visitTypeInsn(NEW, "java/lang/Object");
        method.visitInsn(DUP);
        method.visitMethodInsn(
                INVOKESPECIAL,
                "java/lang/Object",
                "<init>",
                "()V",
                false);
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void allocateBytes(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "allocateBytes",
                "(I)[B",
                null,
                null);
        method.visitCode();
        method.visitVarInsn(ILOAD, 0);
        method.visitIntInsn(NEWARRAY, T_BYTE);
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void readStaticField(
            ClassWriter writer,
            int access,
            String name) {
        MethodVisitor method = writer.visitMethod(
                access, name, "()I", null, null);
        method.visitCode();
        method.visitFieldInsn(GETSTATIC, OWNER, "staticState", "I");
        method.visitInsn(IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void readInstanceField(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC, "readInstanceField", "()I", null, null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitFieldInsn(GETFIELD, OWNER, "instanceState", "I");
        method.visitInsn(IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void integerOperation(
            ClassWriter writer,
            String name,
            int opcode) {
        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC, name, "(II)I", null, null);
        method.visitCode();
        method.visitVarInsn(ILOAD, 0);
        method.visitVarInsn(ILOAD, 1);
        method.visitInsn(opcode);
        method.visitInsn(IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void callStringValueOf(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "callStringValueOf",
                "(Ljava/lang/Object;)Ljava/lang/String;",
                null,
                null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitMethodInsn(
                INVOKESTATIC,
                "java/lang/String",
                "valueOf",
                "(Ljava/lang/Object;)Ljava/lang/String;",
                false);
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void alwaysThrow(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "alwaysThrow",
                "(Ljava/lang/Throwable;)V",
                null,
                null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitInsn(ATHROW);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void synchronizedIdentity(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_SYNCHRONIZED,
                "synchronizedIdentity",
                "(I)I",
                null,
                null);
        method.visitCode();
        method.visitVarInsn(ILOAD, 1);
        method.visitInsn(IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void narrowIdentity(
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
