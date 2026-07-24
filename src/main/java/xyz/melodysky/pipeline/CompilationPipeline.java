package xyz.melodysky.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import xyz.melodysky.diagnostic.DiagnosticStage;

public final class CompilationPipeline {
    private final List<PipelineStage<Object, Object>> stages;

    @SuppressWarnings("unchecked")
    public CompilationPipeline(List<? extends PipelineStage<?, ?>> stages) {
        Objects.requireNonNull(stages, "stages");
        this.stages = stages.stream()
                .map(stage -> (PipelineStage<Object, Object>) stage)
                .toList();
    }

    public PipelineRunResult run(Object initialArtifact, PipelineContext context) {
        Objects.requireNonNull(context, "context");
        Object current = initialArtifact;
        ArrayList<DiagnosticStage> completedStages = new ArrayList<>();
        LinkedHashMap<DiagnosticStage, Object> stageArtifacts = new LinkedHashMap<>();

        for (PipelineStage<Object, Object> stage : stages) {
            StageResult<Object> result = stage.run(current, context);
            context.diagnostics().addAll(result.diagnostics());
            completedStages.add(stage.name());
            result.artifact().ifPresent(artifact -> stageArtifacts.put(stage.name(), artifact));

            if (result.hasErrors() || result.artifact().isEmpty()) {
                return new PipelineRunResult(
                        Optional.empty(),
                        context.diagnostics().diagnostics(),
                        completedStages,
                        true,
                        stageArtifacts);
            }
            current = result.artifact().get();
        }

        return new PipelineRunResult(
                Optional.ofNullable(current),
                context.diagnostics().diagnostics(),
                completedStages,
                false,
                stageArtifacts);
    }
}
