package xyz.melodysky.packaging;

import java.util.Locale;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class FallbackHelperClassFactory implements Opcodes {
    public FallbackHelperClass create(String originalMethodId, String originalMethodKey, String ownerInternalName) {
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
        if (originalMethodKey.contains("#substring!(Ljava/lang/String;)Ljava/lang/String;")) {
            emitSubstringFallback(writer);
        }
        writer.visitEnd();
        return new FallbackHelperClass(internalName, writer.toByteArray());
    }

    public String helperClassName(String originalMethodId, String ownerInternalName) {
        return "j2ll/generated/fallback/"
                + safeSegment(ownerInternalName)
                + "/Fallback$"
                + safeSegment(originalMethodId);
    }

    private void emitSubstringFallback(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "substring",
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
