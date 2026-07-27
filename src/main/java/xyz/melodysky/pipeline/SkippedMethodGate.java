package xyz.melodysky.pipeline;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticCode;
import xyz.melodysky.diagnostic.DiagnosticStage;

/** Evaluates one invocation's skipped-method approval without owning terminal rendering. */
public final class SkippedMethodGate {
    public Result evaluate(
            List<SkippedMethod> skippedMethods,
            boolean priorFailure,
            SkippedMethodApproval approval) {
        Objects.requireNonNull(skippedMethods, "skippedMethods");
        Objects.requireNonNull(approval, "approval");
        if (skippedMethods.isEmpty()) {
            return finish(
                    approval,
                    skippedMethods,
                    SkippedMethodGateDecision.NOT_REQUIRED,
                    List.of());
        }
        if (priorFailure) {
            return finish(
                    approval,
                    skippedMethods,
                    SkippedMethodGateDecision
                            .NOT_EVALUATED_PRIOR_FAILURE,
                    List.of());
        }
        try {
            if (approval.approve(skippedMethods)) {
                return finish(
                        approval,
                        skippedMethods,
                        SkippedMethodGateDecision.APPROVED,
                        List.of());
            }
            return finish(
                    approval,
                    skippedMethods,
                    SkippedMethodGateDecision.REJECTED,
                    List.of(Diagnostic.error(
                                    DiagnosticStage.LOWERING,
                                    DiagnosticCode.SKIPPED_METHODS_NOT_APPROVED,
                                    "build stopped because skipped methods were not approved")
                            .withDecision("cancelled")));
        } catch (IOException exception) {
            String detail = exception.getMessage() == null
                    || exception.getMessage().isBlank()
                            ? exception.getClass().getSimpleName()
                            : exception.getMessage();
            return finish(
                    approval,
                    skippedMethods,
                    SkippedMethodGateDecision.INPUT_ERROR,
                    List.of(Diagnostic.error(
                                    DiagnosticStage.LOWERING,
                                    DiagnosticCode
                                            .SKIPPED_METHOD_CONFIRMATION_INPUT_FAILED,
                                    "cannot read skipped-method confirmation: "
                                            + detail)
                            .withDecision("inputError")));
        }
    }

    private Result finish(
            SkippedMethodApproval approval,
            List<SkippedMethod> skippedMethods,
            SkippedMethodGateDecision decision,
            List<Diagnostic> diagnostics) {
        SkippedMethodGateEvidence evidence =
                new SkippedMethodGateEvidence(skippedMethods, decision);
        approval.onEvaluated(evidence);
        return new Result(evidence, diagnostics);
    }

    public record Result(
            SkippedMethodGateEvidence evidence,
            List<Diagnostic> diagnostics) {
        public Result {
            Objects.requireNonNull(evidence, "evidence");
            diagnostics =
                    List.copyOf(Objects.requireNonNull(
                            diagnostics,
                            "diagnostics"));
        }

        public SkippedMethodGateDecision decision() {
            return evidence.decision();
        }
    }
}
