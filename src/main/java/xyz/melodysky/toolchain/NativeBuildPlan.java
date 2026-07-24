package xyz.melodysky.toolchain;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record NativeBuildPlan(List<NativeBuildUnit> units, List<NativeBuildTargetPreflight> targetPreflights) {
    public NativeBuildPlan(List<NativeBuildUnit> units) {
        this(units, units.stream()
                .map(unit -> new NativeBuildTargetPreflight(
                        unit.target(),
                        unit.outputPath(),
                        unit.libraryName(),
                        true,
                        true,
                        "LEGACY_BUILD_UNIT",
                        "build unit was constructed directly without target preflight",
                        "managedZigBuildZigSharedLibrary",
                        platformSdkRequirement(unit.target())))
                .toList());
    }

    public NativeBuildPlan {
        units = units.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(unit -> unit.target().directoryName()))
                .toList();
        targetPreflights = targetPreflights.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(preflight -> preflight.target().directoryName()))
                .toList();
    }

    public List<NativeBuildTargetPreflight> buildableTargetPreflights() {
        return targetPreflights.stream().filter(NativeBuildTargetPreflight::buildable).toList();
    }

    public List<NativeBuildTargetPreflight> skippedTargetPreflights() {
        return List.of();
    }

    public List<NativeBuildTargetPreflight> failedTargetPreflights() {
        return targetPreflights.stream().filter(preflight -> !preflight.buildable()).toList();
    }

    public NativeBuildPlan withBuildFailures(
            List<TargetTriple> failedTargets,
            String failureKind,
            String buildLogTail) {
        Set<TargetTriple> failed = Set.copyOf(failedTargets);
        List<NativeBuildTargetPreflight> updated = targetPreflights.stream()
                .map(preflight -> failed.contains(preflight.target())
                        ? new NativeBuildTargetPreflight(
                                preflight.target(),
                                preflight.outputPath(),
                                preflight.libraryName(),
                                preflight.currentHost(),
                                false,
                                "ZIG_TARGET_UNBUILDABLE",
                                "managed Zig failed to produce the required target artifact",
                                preflight.requiredCapability(),
                                preflight.platformSdkRequirement(),
                                preflight.required(),
                                failureKind,
                                buildLogTail)
                        : preflight)
                .toList();
        return new NativeBuildPlan(
                updated.stream()
                        .filter(NativeBuildTargetPreflight::buildable)
                        .map(NativeBuildTargetPreflight::asBuildUnit)
                        .toList(),
                updated);
    }

    private static String platformSdkRequirement(TargetTriple target) {
        return switch (target) {
            case MACOS_X64, MACOS_ARM64 -> "macOS SDK and linker support for selected target";
            case WINDOWS_X64, WINDOWS_ARM64 -> "Zig COFF/Windows libc support for selected target";
            case LINUX_X64, LINUX_ARM64 -> "Zig Linux libc/linker support for selected target";
        };
    }
}
