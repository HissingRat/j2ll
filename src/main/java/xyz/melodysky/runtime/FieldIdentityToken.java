package xyz.melodysky.runtime;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public final class FieldIdentityToken {
    private FieldIdentityToken() {}

    public static long token(String fieldKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(Objects.requireNonNull(fieldKey, "fieldKey").getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest, 0, Long.BYTES).getLong() & Long.MAX_VALUE;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static String symbolSuffix(String fieldKey) {
        return "f" + HexFormat.of().toHexDigits(token(fieldKey));
    }
}
