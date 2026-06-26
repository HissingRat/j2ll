package xyz.melodysky.backend.llvm;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import xyz.melodysky.ir.model.IrMethod;

public final class LlvmNameMangler {
    private final boolean obfuscate;
    private final long seed;

    public LlvmNameMangler() {
        this(false, 0L);
    }

    private LlvmNameMangler(boolean obfuscate, long seed) {
        this.obfuscate = obfuscate;
        this.seed = seed;
    }

    public static LlvmNameMangler obfuscating(long seed) {
        return new LlvmNameMangler(true, seed);
    }

    public String functionName(IrMethod method) {
        if (obfuscate) {
            return obfuscated(method.methodKey());
        }
        return "j2ll_" + sanitize(method.owner()) + "_" + sanitize(method.name()) + "_" + hash(method.descriptor());
    }

    public String functionName(String methodKey) {
        if (obfuscate) {
            return obfuscated(methodKey);
        }
        int ownerEnd = methodKey.indexOf('#');
        int descriptorStart = methodKey.indexOf('!');
        if (ownerEnd < 0 || descriptorStart < ownerEnd) {
            throw new IllegalArgumentException("invalid method key: " + methodKey);
        }
        String owner = methodKey.substring(0, ownerEnd);
        String name = methodKey.substring(ownerEnd + 1, descriptorStart);
        String descriptor = methodKey.substring(descriptorStart + 1);
        return "j2ll_" + sanitize(owner) + "_" + sanitize(name) + "_" + hash(descriptor);
    }

    private String sanitize(String value) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if ((ch >= 'a' && ch <= 'z')
                    || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9')) {
                builder.append(ch);
            } else {
                builder.append('_');
            }
        }
        return builder.toString();
    }

    private String hash(String descriptor) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(descriptor.getBytes(java.nio.charset.StandardCharsets.UTF_8)), 0, 8);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String obfuscated(String methodKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Long.toString(seed).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte) ':');
            digest.update("LLVM_NAME_OBFUSCATION".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte) ':');
            digest.update(methodKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "j2ll_f_" + HexFormat.of().formatHex(digest.digest(), 0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
