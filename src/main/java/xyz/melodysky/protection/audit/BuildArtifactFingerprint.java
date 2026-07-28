package xyz.melodysky.protection.audit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public record BuildArtifactFingerprint(
        String nativeSha256,
        String generatedCSha256,
        String combinedSha256,
        long nativeSizeBytes,
        long generatedCSizeBytes) {
    private static final byte[] DOMAIN =
            "j2ll/attacker-audit/fingerprint/v1\0"
                    .getBytes(StandardCharsets.UTF_8);

    public BuildArtifactFingerprint {
        requireHash(nativeSha256, "nativeSha256");
        requireHash(generatedCSha256, "generatedCSha256");
        requireHash(combinedSha256, "combinedSha256");
        if (nativeSizeBytes < 0 || generatedCSizeBytes < 0) {
            throw new IllegalArgumentException(
                    "artifact fingerprint sizes must be non-negative");
        }
    }

    public static BuildArtifactFingerprint capture(
            Path nativeLibrary,
            Path generatedC) throws IOException {
        Objects.requireNonNull(nativeLibrary, "nativeLibrary");
        Objects.requireNonNull(generatedC, "generatedC");
        return of(
                Files.readAllBytes(nativeLibrary),
                Files.readAllBytes(generatedC));
    }

    public static BuildArtifactFingerprint of(
            byte[] nativeBytes,
            byte[] generatedCBytes) {
        Objects.requireNonNull(nativeBytes, "nativeBytes");
        Objects.requireNonNull(generatedCBytes, "generatedCBytes");
        String nativeHash = sha256(nativeBytes);
        String generatedCHash = sha256(generatedCBytes);
        MessageDigest combined = digest();
        combined.update(DOMAIN);
        combined.update(nativeHash.getBytes(StandardCharsets.US_ASCII));
        combined.update((byte) 0);
        combined.update(generatedCHash.getBytes(StandardCharsets.US_ASCII));
        return new BuildArtifactFingerprint(
                nativeHash,
                generatedCHash,
                HexFormat.of().formatHex(combined.digest()),
                nativeBytes.length,
                generatedCBytes.length);
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void requireHash(String hash, String name) {
        Objects.requireNonNull(hash, name);
        if (!hash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lower-case SHA-256");
        }
    }
}
