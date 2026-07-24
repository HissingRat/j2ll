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
        return functionName(method.methodKey());
    }

    public String functionName(String methodKey) {
        int ownerEnd = methodKey.indexOf('#');
        int descriptorStart = methodKey.indexOf('!');
        if (ownerEnd < 0 || descriptorStart < ownerEnd) {
            throw new IllegalArgumentException("invalid method key: " + methodKey);
        }
        return obfuscate
                ? obfuscated(methodKey)
                : hashOnly("LLVM_LINKABLE_FUNCTION", methodKey);
    }

    private String obfuscated(String methodKey) {
        return hashOnly("LLVM_NAME_OBFUSCATION:" + seed, methodKey);
    }

    private String hashOnly(String domain, String methodKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(domain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(methodKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "j2ll_f_" + HexFormat.of().formatHex(digest.digest(), 0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
