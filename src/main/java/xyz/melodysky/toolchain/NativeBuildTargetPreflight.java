package xyz.melodysky.toolchain;

import java.nio.file.Path;
import java.util.Objects;

public record NativeBuildTargetPreflight(
        TargetTriple target,
        Path outputPath,
        String libraryName,
        boolean currentHost,
        boolean buildable,
        String reasonCode,
        String reason,
        String requiredCapability,
        String platformSdkRequirement) {
    public NativeBuildTargetPreflight {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(outputPath, "outputPath");
        Objects.requireNonNull(libraryName, "libraryName");
        Objects.requireNonNull(reasonCode, "reasonCode");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(requiredCapability, "requiredCapability");
        Objects.requireNonNull(platformSdkRequirement, "platformSdkRequirement");
        if (reasonCode.isBlank()) {
            throw new IllegalArgumentException("reasonCode must not be blank");
        }
        if (reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
    }

    public NativeBuildUnit asBuildUnit() {
        if (!buildable) {
            throw new IllegalStateException("target is not buildable in this preflight: " + target.directoryName());
        }
        return new NativeBuildUnit(target, outputPath, libraryName);
    }

    public String status() {
        return buildable ? "buildable" : "skipped";
    }

    public String zigTarget() {
        return target.zigTarget();
    }
}
