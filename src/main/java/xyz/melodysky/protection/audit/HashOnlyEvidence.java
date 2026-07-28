package xyz.melodysky.protection.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Domain-separated SHA-256 helper for attacker-audit evidence identities. */
public final class HashOnlyEvidence {
    private static final byte[] PREFIX =
            "j2ll/attacker-audit/evidence/v1\0"
                    .getBytes(StandardCharsets.UTF_8);

    private HashOnlyEvidence() {}

    public static String sha256(String domain, String value) {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(value, "value");
        if (domain.isBlank()) {
            throw new IllegalArgumentException("hash evidence domain must not be blank");
        }
        MessageDigest digest = digest();
        digest.update(PREFIX);
        digest.update(domain.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest());
    }

    static String requireSha256(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    name + " must be a lower-case SHA-256");
        }
        return value;
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
