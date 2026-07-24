package xyz.melodysky.toolchain;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class NativeBuildPlanner {
    private final Optional<HostPlatform> hostPlatform;
    private final ManagedZigTargetCapabilities targetCapabilities;
    private final NativeArtifactLayout artifactLayout = new NativeArtifactLayout();

    public NativeBuildPlanner() {
        this(HostPlatform.detect(), new ManagedZigTargetCapabilities());
    }

    public NativeBuildPlanner(Optional<HostPlatform> hostPlatform) {
        this(hostPlatform, new ManagedZigTargetCapabilities());
    }

    NativeBuildPlanner(
            Optional<HostPlatform> hostPlatform,
            ManagedZigTargetCapabilities targetCapabilities) {
        this.hostPlatform = hostPlatform;
        this.targetCapabilities = targetCapabilities;
    }

    public NativeBuildPlan plan(Path workspaceRoot, String libraryName, List<TargetTriple> targets) {
        ArrayList<NativeBuildTargetPreflight> preflights = new ArrayList<>();
        for (TargetTriple target : new ZigTargetMatrix(targets).targets()) {
            Path output = artifactLayout.libraryPath(workspaceRoot, target);
            boolean currentHost = hostPlatform.map(host -> host.target() == target).orElse(false);
            ZigTargetCapability capability = targetCapabilities.capability(target, currentHost);
            preflights.add(new NativeBuildTargetPreflight(
                    target,
                    output,
                    libraryName,
                    currentHost,
                    capability.buildable(),
                    capability.reasonCode(),
                    capability.reason(),
                    capability.requiredCapability(),
                    capability.platformSdkRequirement(),
                    true,
                    capability.failureKind(),
                    capability.buildLogTail()));
        }
        return new NativeBuildPlan(
                preflights.stream()
                        .filter(NativeBuildTargetPreflight::buildable)
                        .map(NativeBuildTargetPreflight::asBuildUnit)
                        .toList(),
                preflights);
    }
}
