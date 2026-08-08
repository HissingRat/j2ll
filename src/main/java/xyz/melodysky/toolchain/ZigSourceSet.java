package xyz.melodysky.toolchain;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record ZigSourceSet(
        List<Path> llvmSources,
        List<Path> cSources,
        List<Path> objectInputs,
        List<Path> includeDirectories,
        NativeLibcRequirementPlan libcRequirement,
        NativeLlvmSourcePlan llvmUnwindSources) {
    public ZigSourceSet(
            List<Path> llvmSources,
            List<Path> cSources,
            List<Path> objectInputs,
            List<Path> includeDirectories) {
        this(
                llvmSources,
                cSources,
                objectInputs,
                includeDirectories,
                NativeLibcRequirementPlan.retaining(),
                NativeLlvmSourcePlan.retaining(llvmSources));
    }

    public ZigSourceSet(
            List<Path> llvmSources,
            List<Path> cSources,
            List<Path> objectInputs,
            List<Path> includeDirectories,
            NativeLibcRequirementPlan libcRequirement) {
        this(
                llvmSources,
                cSources,
                objectInputs,
                includeDirectories,
                libcRequirement,
                NativeLlvmSourcePlan.retaining(llvmSources));
    }

    public ZigSourceSet {
        llvmSources = llvmSources.stream().filter(Objects::nonNull).sorted().toList();
        cSources = cSources.stream().filter(Objects::nonNull).sorted().toList();
        objectInputs = objectInputs.stream().filter(Objects::nonNull).sorted().toList();
        includeDirectories = includeDirectories.stream().filter(Objects::nonNull).sorted().toList();
        libcRequirement = Objects.requireNonNull(libcRequirement, "libcRequirement");
        llvmUnwindSources = Objects.requireNonNull(llvmUnwindSources, "llvmUnwindSources");
        List<Path> retainedPaths = llvmUnwindSources.retainedPaths().stream()
                .sorted()
                .toList();
        List<Path> normalizedLlvmSources = llvmSources.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .sorted()
                .toList();
        if (!normalizedLlvmSources.equals(retainedPaths)) {
            throw new IllegalArgumentException(
                    "LLVM sources and unwind source plan retained paths must match exactly");
        }
    }
}
