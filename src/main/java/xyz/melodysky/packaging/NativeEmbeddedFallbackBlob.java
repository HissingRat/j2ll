package xyz.melodysky.packaging;

import java.util.Objects;

public record NativeEmbeddedFallbackBlob(
        String originalMethodId,
        String originalMethodKey,
        String helperClassName,
        String fallbackInvokeDescriptor,
        String fallbackReasonCode,
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
        String definitionMechanismReasonCode,
        boolean hiddenClassApiAvailable,
        boolean ownerLookupSupported,
        String definitionMechanismReason,
        String cacheReasonCode,
        String classloaderReusePolicy,
        String cacheScope,
        String cacheKey,
        String cacheLifetime,
        String globalReferencePolicy) {
    public NativeEmbeddedFallbackBlob {
        Objects.requireNonNull(originalMethodId, "originalMethodId");
        Objects.requireNonNull(originalMethodKey, "originalMethodKey");
        Objects.requireNonNull(helperClassName, "helperClassName");
        Objects.requireNonNull(fallbackInvokeDescriptor, "fallbackInvokeDescriptor");
        Objects.requireNonNull(fallbackReasonCode, "fallbackReasonCode");
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(originalSha256, "originalSha256");
        Objects.requireNonNull(encodedSha256, "encodedSha256");
        Objects.requireNonNull(encodingVersion, "encodingVersion");
        Objects.requireNonNull(compressionAlgorithm, "compressionAlgorithm");
        Objects.requireNonNull(encryptionAlgorithm, "encryptionAlgorithm");
        Objects.requireNonNull(requiredJavaVersion, "requiredJavaVersion");
        Objects.requireNonNull(storageTarget, "storageTarget");
        Objects.requireNonNull(definitionMechanism, "definitionMechanism");
        Objects.requireNonNull(definitionMechanismReasonCode, "definitionMechanismReasonCode");
        Objects.requireNonNull(definitionMechanismReason, "definitionMechanismReason");
        Objects.requireNonNull(cacheReasonCode, "cacheReasonCode");
        Objects.requireNonNull(classloaderReusePolicy, "classloaderReusePolicy");
        Objects.requireNonNull(cacheScope, "cacheScope");
        Objects.requireNonNull(cacheKey, "cacheKey");
        Objects.requireNonNull(cacheLifetime, "cacheLifetime");
        Objects.requireNonNull(globalReferencePolicy, "globalReferencePolicy");
    }
}
