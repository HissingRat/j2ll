package xyz.melodysky.toolchain;

import java.nio.file.Path;
import java.util.Objects;

public final class NativeArtifactLayout {
    public Path outputDirectory(Path workspaceRoot) {
        return Objects.requireNonNull(workspaceRoot, "workspaceRoot").resolve("native");
    }

    public Path libraryPath(Path workspaceRoot, TargetTriple target) {
        Objects.requireNonNull(target, "target");
        return outputDirectory(workspaceRoot).resolve(target.libraryFileName());
    }
}
