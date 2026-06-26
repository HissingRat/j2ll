package xyz.melodysky.packaging;

import java.util.Objects;

public record EncodedFallbackBlob(
        byte[] originalBytes,
        byte[] encodedBytes,
        byte[] keyBytes,
        String originalSha256,
        String encodedSha256,
        String compressionAlgorithm,
        String encryptionAlgorithm,
        String encodingVersion) {
    public EncodedFallbackBlob {
        originalBytes = Objects.requireNonNull(originalBytes, "originalBytes").clone();
        encodedBytes = Objects.requireNonNull(encodedBytes, "encodedBytes").clone();
        keyBytes = Objects.requireNonNull(keyBytes, "keyBytes").clone();
        Objects.requireNonNull(originalSha256, "originalSha256");
        Objects.requireNonNull(encodedSha256, "encodedSha256");
        Objects.requireNonNull(compressionAlgorithm, "compressionAlgorithm");
        Objects.requireNonNull(encryptionAlgorithm, "encryptionAlgorithm");
        Objects.requireNonNull(encodingVersion, "encodingVersion");
    }

    @Override
    public byte[] originalBytes() {
        return originalBytes.clone();
    }

    @Override
    public byte[] encodedBytes() {
        return encodedBytes.clone();
    }

    @Override
    public byte[] keyBytes() {
        return keyBytes.clone();
    }
}
