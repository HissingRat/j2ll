package xyz.melodysky.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticCode;
import xyz.melodysky.diagnostic.DiagnosticStage;

class CompilationPipelineTest {
    @Test
    void runsStagesInOrderAndAggregatesDiagnostics() {
        PipelineStage<String, String> parse = new AppendStage(DiagnosticStage.PARSE, "p");
        PipelineStage<String, String> cfg = new AppendStage(DiagnosticStage.CFG, "c");

        PipelineRunResult result = new CompilationPipeline(List.of(parse, cfg))
                .run("", PipelineContext.bootstrap());

        assertFalse(result.halted());
        assertEquals("pc", result.artifact().orElseThrow());
        assertEquals("pc", result.artifactAs(String.class).orElseThrow());
        assertEquals("p", result.stageArtifact(DiagnosticStage.PARSE, String.class).orElseThrow());
        assertEquals("pc", result.stageArtifact(DiagnosticStage.CFG, String.class).orElseThrow());
        assertTrue(result.stageArtifact(DiagnosticStage.CFG, Integer.class).isEmpty());
        assertEquals(List.of(DiagnosticStage.PARSE, DiagnosticStage.CFG), result.completedStages());
        assertEquals(2, result.diagnostics().size());
    }

    @Test
    void haltsAfterStageError() {
        PipelineStage<String, String> parse = new ErrorStage();
        PipelineStage<String, String> cfg = new AppendStage(DiagnosticStage.CFG, "unreachable");

        PipelineRunResult result = new CompilationPipeline(List.of(parse, cfg))
                .run("", PipelineContext.bootstrap());

        assertTrue(result.halted());
        assertTrue(result.artifact().isEmpty());
        assertEquals(List.of(DiagnosticStage.PARSE), result.completedStages());
        assertEquals(1, result.diagnostics().size());
    }

    private record AppendStage(DiagnosticStage stage, String suffix) implements PipelineStage<String, String> {
        @Override
        public DiagnosticStage name() {
            return stage;
        }

        @Override
        public StageResult<String> run(String input, PipelineContext context) {
            Diagnostic diagnostic = Diagnostic.info(stage, DiagnosticCode.BOOTSTRAP_STAGE_RAN, "stage ran");
            return StageResult.complete(stage, input + suffix, List.of(diagnostic));
        }
    }

    private static final class ErrorStage implements PipelineStage<String, String> {
        @Override
        public DiagnosticStage name() {
            return DiagnosticStage.PARSE;
        }

        @Override
        public StageResult<String> run(String input, PipelineContext context) {
            Diagnostic diagnostic = Diagnostic.error(
                    DiagnosticStage.PARSE,
                    DiagnosticCode.of("BOOTSTRAP_ERROR"),
                    "bootstrap error");
            return StageResult.failed(DiagnosticStage.PARSE, List.of(diagnostic));
        }
    }
}
