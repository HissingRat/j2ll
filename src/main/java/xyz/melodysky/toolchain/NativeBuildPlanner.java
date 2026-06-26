package xyz.melodysky.toolchain;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class NativeBuildPlanner {
    private final Optional<HostPlatform> hostPlatform;

    public NativeBuildPlanner() {
        this(HostPlatform.detect());
    }

    public NativeBuildPlanner(Optional<HostPlatform> hostPlatform) {
        this.hostPlatform = hostPlatform;
    }

    public NativeBuildPlan plan(Path workspaceRoot, String libraryName, List<TargetTriple> targets) {
        ArrayList<NativeBuildTargetPreflight> preflights = new ArrayList<>();
        for (TargetTriple target : new ZigTargetMatrix(targets).targets()) {
            Path output = workspaceRoot.resolve("native").resolve(target.directoryName()).resolve(target.libraryFileName());
            boolean currentHost = hostPlatform.map(host -> host.target() == target).orElse(false);
            boolean buildable = currentHost;
            String reasonCode = reasonCode(target, currentHost);
            preflights.add(new NativeBuildTargetPreflight(
                    target,
                    output,
                    libraryName,
                    currentHost,
                    buildable,
                    reasonCode,
                    reason(target, reasonCode),
                    "managedZig0.15.2BuildZigSharedLibrary",
                    platformSdkRequirement(target)));
        }
        return new NativeBuildPlan(
                preflights.stream()
                        .filter(NativeBuildTargetPreflight::buildable)
                        .map(NativeBuildTargetPreflight::asBuildUnit)
                        .toList(),
                preflights);
    }

    private String reasonCode(TargetTriple target, boolean currentHost) {
        if (currentHost) {
            return "CURRENT_HOST_TARGET";
        }
        if (hostPlatform.isEmpty()) {
            return "UNSUPPORTED_HOST_PLATFORM";
        }
        return "NON_HOST_TARGET_PREFLIGHT_ONLY";
    }

    private String reason(TargetTriple target, String reasonCode) {
        return switch (reasonCode) {
            case "CURRENT_HOST_TARGET" -> "selected target matches the current JVM host and is buildable now";
            case "UNSUPPORTED_HOST_PLATFORM" -> "current JVM host could not be mapped to a supported j2ll target";
            default -> "selected target " + target.directoryName()
                    + " is recorded in the build plan, but this slice only builds the current host target";
        };
    }

    private String platformSdkRequirement(TargetTriple target) {
        return switch (target) {
            case MACOS_X64, MACOS_ARM64 -> "macOS SDK and linker support for selected target";
            case WINDOWS_X64, WINDOWS_ARM64 -> "Zig COFF/Windows libc support for selected target";
            case LINUX_X64, LINUX_ARM64 -> "Zig Linux libc/linker support for selected target";
        };
    }
}
