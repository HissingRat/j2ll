package xyz.melodysky.toolchain;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ZigNativeBuildResult(
        ManagedZig zig,
        Path buildZigPath,
        Path manifestPath,
        Path wrapperSourcePath,
        List<NativeLibraryArtifact> artifacts,
        ZigBuildInvocation invocation) {
    public ZigNativeBuildResult {
        Objects.requireNonNull(zig, "zig");
        Objects.requireNonNull(buildZigPath, "buildZigPath");
        Objects.requireNonNull(manifestPath, "manifestPath");
        Objects.requireNonNull(wrapperSourcePath, "wrapperSourcePath");
        artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
        Objects.requireNonNull(invocation, "invocation");
    }

    public Optional<NativeLibraryArtifact> artifactFor(TargetTriple target) {
        return artifacts.stream().filter(artifact -> artifact.target() == target).findFirst();
    }

    public List<String> exportedSymbols() {
        return artifacts.stream()
                .flatMap(artifact -> artifact.exportedSymbols().stream())
                .distinct()
                .sorted()
                .toList();
    }
}
