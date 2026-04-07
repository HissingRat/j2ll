package xyz.melodysky.backend.llvm;

import xyz.melodysky.ir.model.IrClass;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.util.IrDescriptors;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Locale;

public final class JniMangler {

    private static final String SYMBOL_PREFIX = randomSymbolPrefix();

    private JniMangler() {
    }

    public static String nativeBridgeName(IrClass irClass, IrMethod method) {
        return nativeBridgeName(irClass.reference().internalName(), method.name(), IrDescriptors.methodDescriptor(method));
    }

    public static String nativeBridgeName(String ownerInternalName, String methodName, String methodDescriptor) {
        return opaqueSymbol("jni|" + ownerInternalName + "|" + methodName + "|" + methodDescriptor, 32);
    }

    public static String opaqueSymbol(String identity, int digestLength) {
        return SYMBOL_PREFIX + opaqueDigest(identity).substring(0, digestLength);
    }

    public static String symbolPrefix() {
        return SYMBOL_PREFIX;
    }

    public static String opaqueDigest(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return toHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Missing SHA-256 MessageDigest", exception);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte current : bytes) {
            builder.append(String.format(Locale.ROOT, "%02x", current & 0xff));
        }
        return builder.toString();
    }

    private static String randomSymbolPrefix() {
        byte[] bytes = new byte[4];
        new SecureRandom().nextBytes(bytes);
        return "n" + toHex(bytes) + "_";
    }
}
