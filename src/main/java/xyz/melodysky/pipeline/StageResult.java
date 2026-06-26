package xyz.melodysky.pipeline;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticSeverity;
import xyz.melodysky.diagnostic.DiagnosticStage;

public record StageResult<T>(
        DiagnosticStage stage,
        Optional<T> artifact,
        StageOutcome outcome,
        List<Diagnostic> diagnostics) {
    public StageResult {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(outcome, "outcome");
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }

    public static <T> StageResult<T> complete(DiagnosticStage stage, T artifact) {
        return new StageResult<>(stage, Optional.ofNullable(artifact), StageOutcome.COMPLETE, List.of());
    }

    public static <T> StageResult<T> complete(DiagnosticStage stage, T artifact, List<Diagnostic> diagnostics) {
        return new StageResult<>(stage, Optional.ofNullable(artifact), StageOutcome.COMPLETE, diagnostics);
    }

    public static <T> StageResult<T> conservative(DiagnosticStage stage, T artifact, List<Diagnostic> diagnostics) {
        return new StageResult<>(stage, Optional.ofNullable(artifact), StageOutcome.CONSERVATIVE, diagnostics);
    }

    public static <T> StageResult<T> incomplete(DiagnosticStage stage, T artifact, List<Diagnostic> diagnostics) {
        return new StageResult<>(stage, Optional.ofNullable(artifact), StageOutcome.INCOMPLETE, diagnostics);
    }

    public static <T> StageResult<T> failed(DiagnosticStage stage, List<Diagnostic> diagnostics) {
        return new StageResult<>(stage, Optional.empty(), StageOutcome.INCOMPLETE, diagnostics);
    }

    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(diagnostic -> diagnostic.severity() == DiagnosticSeverity.ERROR);
    }

    public boolean isComplete() {
        return outcome == StageOutcome.COMPLETE;
    }

    public boolean isConservative() {
        return outcome == StageOutcome.CONSERVATIVE;
    }
}
