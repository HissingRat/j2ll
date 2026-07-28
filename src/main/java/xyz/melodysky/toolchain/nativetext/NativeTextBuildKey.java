package xyz.melodysky.toolchain.nativetext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Invocation-scoped key material used to diversify encoded native text.
 *
 * <p>The key is intentionally separate from the deterministic compiler pass
 * seed. Production callers can supply fresh build material while focused
 * tests inject a fixed value.</p>
 */
public final class NativeTextBuildKey {
    private final byte[] bytes;

    private NativeTextBuildKey(byte[] bytes) {
        if (bytes.length == 0) {
            throw new IllegalArgumentException("native text build key must not be empty");
        }
        this.bytes = bytes.clone();
    }

    public static NativeTextBuildKey fromBytes(byte[] bytes) {
        return new NativeTextBuildKey(Objects.requireNonNull(bytes, "bytes"));
    }

    public static NativeTextBuildKey fromUtf8(String value) {
        Objects.requireNonNull(value, "value");
        return fromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    public String hashHex() {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || (other instanceof NativeTextBuildKey key
                        && Arrays.equals(bytes, key.bytes));
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }
}
