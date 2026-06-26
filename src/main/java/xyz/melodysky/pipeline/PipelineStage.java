package xyz.melodysky.pipeline;

import xyz.melodysky.diagnostic.DiagnosticStage;

public interface PipelineStage<I, O> {
    DiagnosticStage name();

    StageResult<O> run(I input, PipelineContext context);
}
