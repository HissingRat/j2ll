package xyz.melodysky.packaging;

import java.util.Objects;

public record NativeEmbeddedFallbackBlob(
        String originalMethodId,
        String originalMethodKey,
        String helperClassName,
        String sha256,
        String originalSha256,
        String encodedSha256,
        String encodingVersion,
        int originalSize,
        int encodedSize,
        String compressionAlgorithm,
        String encryptionAlgorithm,
        String requiredJavaVersion,
        String storageTarget,
        String definitionMechanism,
        String classloaderReusePolicy) {
    public NativeEmbeddedFallbackBlob {
        Objects.requireNonNull(originalMethodId, "originalMethodId");
        Objects.requireNonNull(originalMethodKey, "originalMethodKey");
        Objects.requireNonNull(helperClassName, "helperClassName");
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(originalSha256, "originalSha256");
        Objects.requireNonNull(encodedSha256, "encodedSha256");
        Objects.requireNonNull(encodingVersion, "encodingVersion");
        Objects.requireNonNull(compressionAlgorithm, "compressionAlgorithm");
        Objects.requireNonNull(encryptionAlgorithm, "encryptionAlgorithm");
        Objects.requireNonNull(requiredJavaVersion, "requiredJavaVersion");
        Objects.requireNonNull(storageTarget, "storageTarget");
        Objects.requireNonNull(definitionMechanism, "definitionMechanism");
        Objects.requireNonNull(classloaderReusePolicy, "classloaderReusePolicy");
    }
}
