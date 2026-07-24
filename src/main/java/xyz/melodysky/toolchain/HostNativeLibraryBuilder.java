package xyz.melodysky.toolchain;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.packaging.MethodRewriteDecision;
import xyz.melodysky.packaging.NativeRegistrationPlan;
import xyz.melodysky.packaging.RuntimeLoaderPlan;

public final class HostNativeLibraryBuilder {
    private final NativeImplementationPlanner implementationPlanner = new NativeImplementationPlanner();
    private final ZigNativeLibraryBuilder delegate = new ZigNativeLibraryBuilder();

    public Optional<NativeLibraryArtifact> buildIfHostTargetSelected(
            Path workspaceRoot,
            String embeddedLibraryDirectory,
            NativeBuildPlan buildPlan,
            NativeRegistrationPlan registrationPlan,
            List<MethodRewriteDecision> decisions) throws IOException {
        return buildIfHostTargetSelected(
                workspaceRoot,
                embeddedLibraryDirectory,
                buildPlan,
                implementationPlanner.plan(registrationPlan, decisions, Map.of()),
                Map.of());
    }

    public Optional<NativeLibraryArtifact> buildIfHostTargetSelected(
            Path workspaceRoot,
            String embeddedLibraryDirectory,
            NativeBuildPlan buildPlan,
            NativeImplementationPlan implementationPlan,
            Map<String, IrMethod> irMethods) throws IOException {
        Optional<HostPlatform> host = HostPlatform.detect();
        if (host.isEmpty()) {
            return Optional.empty();
        }
        RuntimeLoaderPlan runtimeLoaderPlan = RuntimeLoaderPlan.create(
                embeddedLibraryDirectory,
                implementationPlan.hasNativeEmbeddedFallback());
        Optional<ZigNativeBuildResult> result = delegate.build(
                workspaceRoot,
                runtimeLoaderPlan,
                buildPlan,
                implementationPlan,
                irMethods);
        return result.flatMap(buildResult -> buildResult.artifactFor(host.orElseThrow().target()));
    }
}
