package xyz.melodysky.toolchain;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import xyz.melodysky.toolchain.symbols.NativeUnwindSectionInspection;

public record NativeLibraryArtifact(
        TargetTriple target,
        Path libraryPath,
        Path sourcePath,
        String jarPath,
        String sha256,
        List<String> exportedSymbols,
        Optional<NativeUnwindSectionInspection> unwindSectionInspection) {
    public NativeLibraryArtifact {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(libraryPath, "libraryPath");
        Objects.requireNonNull(sourcePath, "sourcePath");
        Objects.requireNonNull(jarPath, "jarPath");
        Objects.requireNonNull(sha256, "sha256");
        exportedSymbols = List.copyOf(Objects.requireNonNull(exportedSymbols, "exportedSymbols"));
        unwindSectionInspection = Objects.requireNonNull(
                unwindSectionInspection,
                "unwindSectionInspection");
        unwindSectionInspection.ifPresent(inspection -> {
            if (inspection.target() != target) {
                throw new IllegalArgumentException(
                        "native unwind inspection target does not match artifact target");
            }
        });
    }

    public NativeLibraryArtifact(
            TargetTriple target,
            Path libraryPath,
            Path sourcePath,
            String jarPath,
            String sha256,
            List<String> exportedSymbols) {
        this(
                target,
                libraryPath,
                sourcePath,
                jarPath,
                sha256,
                exportedSymbols,
                Optional.empty());
    }
}
