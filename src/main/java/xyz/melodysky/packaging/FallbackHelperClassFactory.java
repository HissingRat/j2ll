package xyz.melodysky.packaging;

import java.util.Locale;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.MethodNode;
import xyz.melodysky.frontend.classfile.ParsedMethod;

public final class FallbackHelperClassFactory implements Opcodes {
    public static final String HELPER_METHOD_NAME = "invoke";

    public FallbackHelperClass create(String originalMethodId, String originalMethodKey, String ownerInternalName) {
        return create(
                originalMethodId,
                originalMethodKey,
                ownerInternalName,
                FallbackBlobInput.methodName(originalMethodKey),
                FallbackBlobInput.descriptor(originalMethodKey),
                true,
                null);
    }

    public FallbackHelperClass create(String originalMethodId, ParsedMethod method) {
        return create(
                originalMethodId,
                method.methodKey(),
                method.owner(),
                method.name(),
                method.descriptor(),
                method.accessFlags().isStatic(),
                method.methodNode());
    }

    public FallbackHelperClass create(FallbackBlobInput input) {
        return create(
                input.originalMethodId(),
                input.originalMethodKey(),
                input.ownerInternalName(),
                input.methodName(),
                input.descriptor(),
                input.staticMethod(),
                input.methodNode());
    }

    private FallbackHelperClass create(
            String originalMethodId,
            String originalMethodKey,
            String ownerInternalName,
            String methodName,
            String descriptor,
            boolean staticMethod,
            MethodNode methodNode) {
        String internalName = helperClassName(originalMethodId, ownerInternalName);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, internalName, null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(ACC_PRIVATE, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        if (methodNode != null) {
            emitOriginalBodyFallback(writer, methodNode, ownerInternalName, descriptor, staticMethod);
        } else if (originalMethodKey.contains("#substring!(Ljava/lang/String;)Ljava/lang/String;")) {
            emitLegacySubstringFallback(writer);
        } else {
            throw new IllegalArgumentException("fallback helper requires original bytecode for " + originalMethodKey);
        }
        writer.visitEnd();
        return new FallbackHelperClass(internalName, writer.toByteArray());
    }

    public String helperClassName(String originalMethodId, String ownerInternalName) {
        int slash = ownerInternalName.lastIndexOf('/');
        String packagePrefix = slash < 0 ? "" : ownerInternalName.substring(0, slash + 1);
        return packagePrefix + "J2llFallback$" + safeSegment(originalMethodId);
    }

    public String helperDescriptor(String ownerInternalName, String descriptor, boolean staticMethod) {
        if (staticMethod) {
            return descriptor;
        }
        Type[] arguments = Type.getArgumentTypes(descriptor);
        StringBuilder result = new StringBuilder();
        result.append("(L").append(ownerInternalName).append(';');
        for (Type argument : arguments) {
            result.append(argument.getDescriptor());
        }
        result.append(')').append(Type.getReturnType(descriptor).getDescriptor());
        return result.toString();
    }

    private void emitOriginalBodyFallback(
            ClassWriter writer,
            MethodNode original,
            String ownerInternalName,
            String descriptor,
            boolean staticMethod) {
        String helperDescriptor = helperDescriptor(ownerInternalName, descriptor, staticMethod);
        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC | ACC_SYNTHETIC,
                HELPER_METHOD_NAME,
                helperDescriptor,
                null,
                original.exceptions == null ? null : original.exceptions.toArray(String[]::new));
        original.accept(method);
    }

    private void emitLegacySubstringFallback(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                HELPER_METHOD_NAME,
                "(Ljava/lang/String;)Ljava/lang/String;",
                null,
                null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitInsn(ICONST_1);
        method.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "substring", "(I)Ljava/lang/String;", false);
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private String safeSegment(String value) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if ((ch >= 'a' && ch <= 'z')
                    || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9')
                    || ch == '_'
                    || ch == '$') {
                result.append(ch);
            } else {
                result.append('_');
                if (ch > 127) {
                    result.append(Integer.toHexString(ch).toLowerCase(Locale.ROOT));
                    result.append('_');
                }
            }
        }
        return result.toString();
    }
}
