package xyz.melodysky.pipeline;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticStage;

public record PipelineRunResult(
        Optional<Object> artifact,
        List<Diagnostic> diagnostics,
        List<DiagnosticStage> completedStages,
        boolean halted,
        Map<DiagnosticStage, Object> stageArtifacts) {
    public PipelineRunResult {
        Objects.requireNonNull(artifact, "artifact");
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        completedStages = List.copyOf(Objects.requireNonNull(completedStages, "completedStages"));
        stageArtifacts = Map.copyOf(Objects.requireNonNull(stageArtifacts, "stageArtifacts"));
    }

    public PipelineRunResult(
            Optional<Object> artifact,
            List<Diagnostic> diagnostics,
            List<DiagnosticStage> completedStages,
            boolean halted) {
        this(artifact, diagnostics, completedStages, halted, Map.of());
    }

    public <T> Optional<T> artifactAs(Class<T> type) {
        Objects.requireNonNull(type, "type");
        return artifact.filter(type::isInstance).map(type::cast);
    }

    public <T> Optional<T> stageArtifact(DiagnosticStage stage, Class<T> type) {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(type, "type");
        return Optional.ofNullable(stageArtifacts.get(stage)).filter(type::isInstance).map(type::cast);
    }
}
