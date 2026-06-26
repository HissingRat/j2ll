package xyz.melodysky.toolchain;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record ZigSourceSet(
        List<Path> llvmSources,
        List<Path> cSources,
        List<Path> objectInputs,
        List<Path> includeDirectories) {
    public ZigSourceSet {
        llvmSources = llvmSources.stream().filter(Objects::nonNull).sorted().toList();
        cSources = cSources.stream().filter(Objects::nonNull).sorted().toList();
        objectInputs = objectInputs.stream().filter(Objects::nonNull).sorted().toList();
        includeDirectories = includeDirectories.stream().filter(Objects::nonNull).sorted().toList();
    }
}
