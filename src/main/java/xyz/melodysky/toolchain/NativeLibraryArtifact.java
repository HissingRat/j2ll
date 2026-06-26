package xyz.melodysky.toolchain;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record NativeLibraryArtifact(
        TargetTriple target,
        Path libraryPath,
        Path sourcePath,
        String jarPath,
        String sha256,
        List<String> exportedSymbols) {
    public NativeLibraryArtifact {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(libraryPath, "libraryPath");
        Objects.requireNonNull(sourcePath, "sourcePath");
        Objects.requireNonNull(jarPath, "jarPath");
        Objects.requireNonNull(sha256, "sha256");
        exportedSymbols = List.copyOf(Objects.requireNonNull(exportedSymbols, "exportedSymbols"));
    }
}
