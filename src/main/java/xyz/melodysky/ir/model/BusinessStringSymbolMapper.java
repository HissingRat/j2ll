package xyz.melodysky.ir.model;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Build-scoped hash-only symbol mapping for localized business strings. */
public final class BusinessStringSymbolMapper {
    private static final byte[] DOMAIN =
            "j2ll-business-string-helper-v2"
                    .getBytes(StandardCharsets.US_ASCII);
    private static final BusinessStringSymbolMapper COMPATIBILITY =
            fromBytes("j2ll-business-string-symbol-compatibility-v1"
                    .getBytes(StandardCharsets.UTF_8));

    private final byte[] buildKey;

    private BusinessStringSymbolMapper(byte[] buildKey) {
        if (buildKey.length == 0) {
            throw new IllegalArgumentException(
                    "business string symbol build key must not be empty");
        }
        this.buildKey = buildKey.clone();
    }

    public static BusinessStringSymbolMapper fromBytes(byte[] buildKey) {
        return new BusinessStringSymbolMapper(
                Objects.requireNonNull(buildKey, "buildKey"));
    }

    public static BusinessStringSymbolMapper compatibility() {
        return COMPATIBILITY;
    }

    public String symbolFor(BusinessStringConstantRef constant) {
        Objects.requireNonNull(constant, "constant");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateLengthPrefixed(digest, DOMAIN);
            updateLengthPrefixed(digest, buildKey);
            updateLengthPrefixed(
                    digest,
                    constant.value().getBytes(StandardCharsets.UTF_8));
            return "j2ll_rt_string_constant_"
                    + HexFormat.of().formatHex(digest.digest(), 0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void updateLengthPrefixed(
            MessageDigest digest,
            byte[] value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES)
                .putInt(value.length)
                .array());
        digest.update(value);
    }
}
