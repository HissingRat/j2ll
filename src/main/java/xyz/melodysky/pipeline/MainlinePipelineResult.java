package xyz.melodysky.pipeline;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.packaging.NativeRegistrationPlan;
import xyz.melodysky.toolchain.NativeBuildPlan;

public record MainlinePipelineResult(
        Path workspaceRoot,
        Path outputJar,
        List<Diagnostic> diagnostics,
        NativeBuildPlan nativeBuildPlan,
        NativeRegistrationPlan nativeRegistrationPlan,
        boolean successful) {
    public MainlinePipelineResult {
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        Objects.requireNonNull(outputJar, "outputJar");
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        Objects.requireNonNull(nativeBuildPlan, "nativeBuildPlan");
        Objects.requireNonNull(nativeRegistrationPlan, "nativeRegistrationPlan");
    }
}
