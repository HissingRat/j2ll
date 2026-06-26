package xyz.melodysky.toolchain;

import java.nio.file.Path;
import java.util.Objects;

public record NativeBuildUnit(TargetTriple target, Path outputPath, String libraryName) {
    public NativeBuildUnit {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(outputPath, "outputPath");
        Objects.requireNonNull(libraryName, "libraryName");
    }
}
