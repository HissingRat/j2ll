package xyz.melodysky.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticCode;
import xyz.melodysky.diagnostic.DiagnosticStage;

class StageResultTest {
    @Test
    void conservativeResultCarriesFallbackWarningWithoutFailing() {
        Diagnostic fallback = Diagnostic.warning(
                        DiagnosticStage.LOWERING,
                        DiagnosticCode.JVM_HELPER_FALLBACK,
                        "operation uses JVM helper fallback")
                .withDecision(LoweringStatus.HALF_LOWERED.wireName())
                .withConservativeFallbackAvailable(true);

        StageResult<String> result = StageResult.conservative(
                DiagnosticStage.LOWERING,
                "ssa-artifact",
                List.of(fallback));

        assertTrue(result.isConservative());
        assertFalse(result.hasErrors());
        assertEquals("ssa-artifact", result.artifact().orElseThrow());
        assertEquals(List.of(fallback), result.diagnostics());
    }

    @Test
    void validatorDiagnosticsAreMergedIntoStageResult() {
        StageResult<String> result = StageResult.complete(DiagnosticStage.CFG, "cfg");
        StageValidator<String> validator = new StageValidator<>() {
            @Override
            public DiagnosticStage stage() {
                return DiagnosticStage.VALIDATION;
            }

            @Override
            public List<Diagnostic> validate(String artifact) {
                return List.of(Diagnostic.info(
                        DiagnosticStage.VALIDATION,
                        DiagnosticCode.BOOTSTRAP_VALIDATION,
                        "validated " + artifact));
            }
        };

        StageResult<String> validated = StageValidation.validate(result, validator);

        assertEquals(1, validated.diagnostics().size());
        assertEquals(DiagnosticStage.VALIDATION, validated.diagnostics().get(0).stage());
    }
}
