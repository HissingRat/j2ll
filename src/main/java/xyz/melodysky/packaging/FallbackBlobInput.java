package xyz.melodysky.packaging;

import java.util.Objects;

public record FallbackBlobInput(
        String originalMethodId,
        String originalMethodKey,
        String ownerInternalName) {
    public FallbackBlobInput {
        Objects.requireNonNull(originalMethodId, "originalMethodId");
        Objects.requireNonNull(originalMethodKey, "originalMethodKey");
        Objects.requireNonNull(ownerInternalName, "ownerInternalName");
    }
}
