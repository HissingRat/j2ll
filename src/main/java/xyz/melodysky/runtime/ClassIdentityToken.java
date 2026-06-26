package xyz.melodysky.runtime;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public final class ClassIdentityToken {
    private ClassIdentityToken() {}

    public static long token(String classIdentity) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(Objects.requireNonNull(classIdentity, "classIdentity").getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest, 0, Long.BYTES).getLong() & Long.MAX_VALUE;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static String symbolSuffix(String classIdentity) {
        return "c" + HexFormat.of().toHexDigits(token(classIdentity));
    }
}
