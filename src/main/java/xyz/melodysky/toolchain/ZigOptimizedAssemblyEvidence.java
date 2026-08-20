package xyz.melodysky.toolchain;

import java.nio.file.Path;
import java.util.Objects;

/** Locates workspace-private optimized assembly used as the actual native link input. */
final class ZigOptimizedAssemblyEvidence {
    private ZigOptimizedAssemblyEvidence() {}

    static Path path(
            ZigBuildWorkspace workspace,
            TargetTriple target,
            ZigBuildProgressPlan.CompileInput compileInput) {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(compileInput, "compileInput");
        if (compileInput.kind() != ZigBuildProgressPlan.CompileInputKind.C) {
            throw new IllegalArgumentException(
                    "optimized assembly evidence is only emitted for C inputs");
        }
        return workspace.buildDirectory()
                .resolve("evidence")
                .resolve("optimized-assembly")
                .resolve(target.directoryName())
                .resolve(compileInput.id() + ".s");
    }
}
