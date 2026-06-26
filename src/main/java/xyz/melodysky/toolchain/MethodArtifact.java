package xyz.melodysky.toolchain;

import java.util.Objects;
import xyz.melodysky.pipeline.LoweringStatus;

public record MethodArtifact(
        String owner,
        String name,
        String descriptor,
        String fullHash,
        int hashPrefixLength,
        String methodId,
        String safeMethodName,
        LoweringStatus status) {
    public MethodArtifact {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(fullHash, "fullHash");
        Objects.requireNonNull(methodId, "methodId");
        Objects.requireNonNull(safeMethodName, "safeMethodName");
        Objects.requireNonNull(status, "status");
    }
}
