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
                    platformSdkRequirement(target),
                    true,
                    failureKind(target, currentHost),
                    buildLogTail(target, currentHost)));
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
            return "ZIG_TARGET_UNBUILDABLE";
        }
        return "ZIG_TARGET_UNBUILDABLE";
    }

    private String reason(TargetTriple target, String reasonCode) {
        return switch (reasonCode) {
            case "CURRENT_HOST_TARGET" -> "selected target matches the current JVM host and is buildable now";
            case "ZIG_TARGET_UNBUILDABLE" -> "selected required target " + target.directoryName()
                    + " is not buildable by the current managed Zig workspace preflight";
            default -> "selected target " + target.directoryName() + " is not buildable by the current preflight";
        };
    }

    private String platformSdkRequirement(TargetTriple target) {
        return switch (target) {
            case MACOS_X64, MACOS_ARM64 -> "macOS SDK and linker support for selected target";
            case WINDOWS_X64, WINDOWS_ARM64 -> "Zig COFF/Windows libc support for selected target";
            case LINUX_X64, LINUX_ARM64 -> "Zig Linux libc/linker support for selected target";
        };
    }

    private String failureKind(TargetTriple target, boolean currentHost) {
        if (currentHost) {
            return "none";
        }
        if (hostPlatform.isEmpty()) {
            return "unknown";
        }
        return switch (target) {
            case MACOS_X64, MACOS_ARM64 -> "missingSdk";
            case WINDOWS_X64, WINDOWS_ARM64 -> "unsupportedLinker";
            case LINUX_X64, LINUX_ARM64 -> "unsupportedLibc";
        };
    }

    private String buildLogTail(TargetTriple target, boolean currentHost) {
        if (currentHost) {
            return "preflight buildable; Zig build log is recorded after invocation";
        }
        return "preflight only: no Zig build invoked for required unbuildable target " + target.directoryName();
    }
}
