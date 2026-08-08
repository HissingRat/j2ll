package xyz.melodysky.toolchain;

import java.util.Objects;

public record NativeUnwindRetentionDecision(
        TargetTriple target,
        boolean requested,
        boolean effective,
        NativeUnwindRetentionReason reason) {
    public NativeUnwindRetentionDecision {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(reason, "reason");
    }
}
