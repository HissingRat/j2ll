package xyz.melodysky.toolchain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;

/** Derives purpose-separated, build-scoped registration control symbols. */
final class NativeRegistrationControlSymbolDeriver {
    private static final byte[] DOMAIN =
            "j2ll/native-registration-control/v1"
                    .getBytes(StandardCharsets.US_ASCII);

    private final byte[] buildKey;

    NativeRegistrationControlSymbolDeriver(
            NativeTextBuildKey buildKey) {
        this.buildKey = Objects.requireNonNull(buildKey, "buildKey")
                .bytes();
    }

    String aggregateSymbol() {
        return derive("aggregate", List.of("translation-unit"));
    }

    String ownerSymbol(String ownerIdentity) {
        return derive(
                "owner",
                List.of(Objects.requireNonNull(
                        ownerIdentity,
                        "ownerIdentity")));
    }

    String chunkSymbol(
            int ordinal,
            List<String> ownerIdentities) {
        if (ordinal < 0) {
            throw new IllegalArgumentException(
                    "registration chunk ordinal must not be negative");
        }
        java.util.ArrayList<String> components =
                new java.util.ArrayList<>();
        components.add(Integer.toString(ordinal));
        components.addAll(Objects.requireNonNull(
                ownerIdentities,
                "ownerIdentities"));
        return derive("forward-chunk", components);
    }

    String failureLeafSymbol(String role) {
        return derive(
                "failure-leaf",
                List.of(Objects.requireNonNull(role, "role")));
    }

    String chunkRemainderRank(int ordinal) {
        return derive(
                "chunk-remainder-rank",
                List.of(Integer.toString(ordinal)));
    }

    private String derive(
            String purpose,
            List<String> components) {
        MessageDigest digest = sha256();
        update(digest, DOMAIN);
        update(digest, buildKey);
        update(
                digest,
                Objects.requireNonNull(purpose, "purpose")
                        .getBytes(StandardCharsets.US_ASCII));
        for (String component : components) {
            update(
                    digest,
                    Objects.requireNonNull(component, "component")
                            .getBytes(StandardCharsets.UTF_8));
        }
        byte[] value = digest.digest();
        StringBuilder symbol = new StringBuilder(32);
        for (int index = 0; index < 16; index++) {
            symbol.append((char) ('a'
                    + ((value[index] >>> 4) & 0x0f)));
            symbol.append((char) ('a'
                    + (value[index] & 0x0f)));
        }
        return symbol.toString();
    }

    private void update(
            MessageDigest digest,
            byte[] value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES)
                .putInt(value.length)
                .array());
        digest.update(value);
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable",
                    exception);
        }
    }
}
