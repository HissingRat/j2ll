package xyz.melodysky.toolchain;

import java.util.Objects;

public record ZigTargetCapability(
        boolean buildable,
        String reasonCode,
        String reason,
        String requiredCapability,
        String platformSdkRequirement,
        String failureKind,
        String buildLogTail) {
    public ZigTargetCapability {
        Objects.requireNonNull(reasonCode, "reasonCode");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(requiredCapability, "requiredCapability");
        Objects.requireNonNull(platformSdkRequirement, "platformSdkRequirement");
        Objects.requireNonNull(failureKind, "failureKind");
        Objects.requireNonNull(buildLogTail, "buildLogTail");
    }
}
