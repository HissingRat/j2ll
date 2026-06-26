package xyz.melodysky.pipeline;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticStage;

public record PipelineRunResult(
        Optional<Object> artifact,
        List<Diagnostic> diagnostics,
        List<DiagnosticStage> completedStages,
        boolean halted) {
    public PipelineRunResult {
        Objects.requireNonNull(artifact, "artifact");
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        completedStages = List.copyOf(Objects.requireNonNull(completedStages, "completedStages"));
    }
}
