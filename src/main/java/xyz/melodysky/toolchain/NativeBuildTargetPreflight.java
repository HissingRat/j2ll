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
        String platformSdkRequirement,
        boolean required,
        String failureKind,
        String buildLogTail) {
    public NativeBuildTargetPreflight(
            TargetTriple target,
            Path outputPath,
            String libraryName,
            boolean currentHost,
            boolean buildable,
            String reasonCode,
            String reason,
            String requiredCapability,
            String platformSdkRequirement) {
        this(
                target,
                outputPath,
                libraryName,
                currentHost,
                buildable,
                reasonCode,
                reason,
                requiredCapability,
                platformSdkRequirement,
                true,
                defaultFailureKind(buildable, reasonCode),
                "");
    }

    public NativeBuildTargetPreflight {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(outputPath, "outputPath");
        Objects.requireNonNull(libraryName, "libraryName");
        Objects.requireNonNull(reasonCode, "reasonCode");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(requiredCapability, "requiredCapability");
        Objects.requireNonNull(platformSdkRequirement, "platformSdkRequirement");
        Objects.requireNonNull(failureKind, "failureKind");
        Objects.requireNonNull(buildLogTail, "buildLogTail");
        if (reasonCode.isBlank()) {
            throw new IllegalArgumentException("reasonCode must not be blank");
        }
        if (reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        if (failureKind.isBlank()) {
            throw new IllegalArgumentException("failureKind must not be blank");
        }
    }

    public NativeBuildUnit asBuildUnit() {
        if (!buildable) {
            throw new IllegalStateException("target is not buildable in this preflight: " + target.directoryName());
        }
        return new NativeBuildUnit(target, outputPath, libraryName);
    }

    public String status() {
        return buildable ? "buildable" : "failed";
    }

    public String zigTarget() {
        return target.zigTarget();
    }

    private static String defaultFailureKind(boolean buildable, String reasonCode) {
        if (buildable) {
            return "none";
        }
        if ("ZIG_TARGET_UNBUILDABLE".equals(reasonCode)) {
            return "unknown";
        }
        return "notApplicable";
    }
}
