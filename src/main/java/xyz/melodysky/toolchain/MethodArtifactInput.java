package xyz.melodysky.toolchain;

import java.util.Objects;
import xyz.melodysky.pipeline.LoweringStatus;

public record MethodArtifactInput(
        String owner,
        String name,
        String descriptor,
        LoweringStatus status) {
    public MethodArtifactInput {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(status, "status");
    }
}
