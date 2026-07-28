package xyz.melodysky.testsupport;

import java.util.List;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** Exact bytecode call shapes covered by the v2 JDK generic-bridge policy batch. */
public final class JdkGenericBridgeFixture {
    private JdkGenericBridgeFixture() {
    }

    public static List<CallSpec> calls() {
        return List.of(
                call(
                        "closeInputStream",
                        "(Ljava/io/InputStream;)V",
                        "java/io/InputStream",
                        "close",
                        "()V",
                        Opcodes.INVOKEVIRTUAL),
                call(
                        "readAllBytes",
                        "(Ljava/io/InputStream;)[B",
                        "java/io/InputStream",
                        "readAllBytes",
                        "()[B",
                        Opcodes.INVOKEVIRTUAL),
                call(
                        "addSuppressed",
                        "(Ljava/lang/Throwable;Ljava/lang/Throwable;)V",
                        "java/lang/Throwable",
                        "addSuppressed",
                        "(Ljava/lang/Throwable;)V",
                        Opcodes.INVOKEVIRTUAL),
                call(
                        "privateLookupIn",
                        "(Ljava/lang/Class;Ljava/lang/invoke/MethodHandles$Lookup;)"
                                + "Ljava/lang/invoke/MethodHandles$Lookup;",
                        "java/lang/invoke/MethodHandles",
                        "privateLookupIn",
                        "(Ljava/lang/Class;Ljava/lang/invoke/MethodHandles$Lookup;)"
                                + "Ljava/lang/invoke/MethodHandles$Lookup;",
                        Opcodes.INVOKESTATIC),
                call(
                        "defineHiddenClass",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;[BZ"
                                + "[Ljava/lang/invoke/MethodHandles$Lookup$ClassOption;)"
                                + "Ljava/lang/invoke/MethodHandles$Lookup;",
                        "java/lang/invoke/MethodHandles$Lookup",
                        "defineHiddenClass",
                        "([BZ[Ljava/lang/invoke/MethodHandles$Lookup$ClassOption;)"
                                + "Ljava/lang/invoke/MethodHandles$Lookup;",
                        Opcodes.INVOKEVIRTUAL),
                call(
                        "lookupClass",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;)Ljava/lang/Class;",
                        "java/lang/invoke/MethodHandles$Lookup",
                        "lookupClass",
                        "()Ljava/lang/Class;",
                        Opcodes.INVOKEVIRTUAL),
                call(
                        "wrap",
                        "([B)Ljava/nio/ByteBuffer;",
                        "java/nio/ByteBuffer",
                        "wrap",
                        "([B)Ljava/nio/ByteBuffer;",
                        Opcodes.INVOKESTATIC),
                call(
                        "getByte",
                        "(Ljava/nio/ByteBuffer;)B",
                        "java/nio/ByteBuffer",
                        "get",
                        "()B",
                        Opcodes.INVOKEVIRTUAL),
                call(
                        "getInto",
                        "(Ljava/nio/ByteBuffer;[B)Ljava/nio/ByteBuffer;",
                        "java/nio/ByteBuffer",
                        "get",
                        "([B)Ljava/nio/ByteBuffer;",
                        Opcodes.INVOKEVIRTUAL),
                call(
                        "remaining",
                        "(Ljava/nio/ByteBuffer;)I",
                        "java/nio/ByteBuffer",
                        "remaining",
                        "()I",
                        Opcodes.INVOKEVIRTUAL),
                call(
                        "fill",
                        "([BB)V",
                        "java/util/Arrays",
                        "fill",
                        "([BB)V",
                        Opcodes.INVOKESTATIC));
    }

    public static byte[] classBytes(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                internalName,
                null,
                "java/lang/Object",
                null);
        for (CallSpec call : calls()) {
            emitWrapper(writer, call);
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void emitWrapper(ClassWriter writer, CallSpec call) {
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                call.wrapperName(),
                call.wrapperDescriptor(),
                null,
                null);
        method.visitCode();
        int local = 0;
        for (Type argument : Type.getArgumentTypes(call.wrapperDescriptor())) {
            method.visitVarInsn(argument.getOpcode(Opcodes.ILOAD), local);
            local += argument.getSize();
        }
        method.visitMethodInsn(
                call.invokeOpcode(),
                call.targetOwner(),
                call.targetName(),
                call.targetDescriptor(),
                false);
        method.visitInsn(Type.getReturnType(call.wrapperDescriptor()).getOpcode(Opcodes.IRETURN));
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static CallSpec call(
            String wrapperName,
            String wrapperDescriptor,
            String targetOwner,
            String targetName,
            String targetDescriptor,
            int invokeOpcode) {
        return new CallSpec(
                wrapperName,
                wrapperDescriptor,
                targetOwner,
                targetName,
                targetDescriptor,
                invokeOpcode);
    }

    public record CallSpec(
            String wrapperName,
            String wrapperDescriptor,
            String targetOwner,
            String targetName,
            String targetDescriptor,
            int invokeOpcode) {
        public String targetMethodKey() {
            return targetOwner + "#" + targetName + "!" + targetDescriptor;
        }
    }
}
