package xyz.melodysky.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.melodysky.diagnostic.DiagnosticCode;
import xyz.melodysky.diagnostic.DiagnosticStage;

class SkippedMethodGateTest {
    private static final SkippedMethod SKIPPED = new SkippedMethod(
            "pkg/Owner",
            "method",
            "()V",
            DiagnosticStage.LOWERING,
            "UNSUPPORTED",
            "unsupported");

    @Test
    void emptyAndPriorFailureDoNotCallApproval() {
        SkippedMethodApproval mustNotRun = ignored -> {
            throw new AssertionError("approval must not be called");
        };

        assertEquals(
                SkippedMethodGateDecision.NOT_REQUIRED,
                new SkippedMethodGate()
                        .evaluate(List.of(), false, mustNotRun)
                        .decision());
        assertEquals(
                SkippedMethodGateDecision.NOT_EVALUATED_PRIOR_FAILURE,
                new SkippedMethodGate()
                        .evaluate(List.of(SKIPPED), true, mustNotRun)
                        .decision());
    }

    @Test
    void approvalRejectionAndInputErrorHaveStableDecisions() {
        assertEquals(
                SkippedMethodGateDecision.APPROVED,
                new SkippedMethodGate()
                        .evaluate(
                                List.of(SKIPPED),
                                false,
                                SkippedMethodApproval.allowAll())
                        .decision());
        SkippedMethodGate.Result rejected = new SkippedMethodGate()
                .evaluate(
                        List.of(SKIPPED),
                        false,
                        SkippedMethodApproval.rejectAll());
        assertEquals(
                SkippedMethodGateDecision.REJECTED,
                rejected.decision());
        assertTrue(rejected.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code().equals(
                        DiagnosticCode.SKIPPED_METHODS_NOT_APPROVED)));

        SkippedMethodGate.Result inputError = new SkippedMethodGate()
                .evaluate(List.of(SKIPPED), false, ignored -> {
                    throw new IOException("broken input");
                });
        assertEquals(
                SkippedMethodGateDecision.INPUT_ERROR,
                inputError.decision());
        assertTrue(inputError.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code().equals(
                        DiagnosticCode
                                .SKIPPED_METHOD_CONFIRMATION_INPUT_FAILED)));
    }
}
