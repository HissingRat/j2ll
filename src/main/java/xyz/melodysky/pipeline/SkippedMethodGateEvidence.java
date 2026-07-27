package xyz.melodysky.pipeline;

import java.util.List;
import java.util.Objects;

/** Immutable skipped-method set and the decision made for the same invocation. */
public record SkippedMethodGateEvidence(
        List<SkippedMethod> methods,
        SkippedMethodGateDecision decision) {
    public SkippedMethodGateEvidence {
        methods = Objects.requireNonNull(methods, "methods").stream()
                .sorted()
                .toList();
        Objects.requireNonNull(decision, "decision");
        boolean requiresMethods = switch (decision) {
            case APPROVED,
                    REJECTED,
                    INPUT_ERROR,
                    NOT_EVALUATED_PRIOR_FAILURE -> true;
            case NOT_ANALYZED, NOT_REQUIRED -> false;
        };
        if ((requiresMethods && methods.isEmpty())
                || (!requiresMethods && !methods.isEmpty())) {
            throw new IllegalArgumentException(
                    decision.wireName()
                            + (requiresMethods
                                    ? " requires skipped methods"
                                    : " requires an empty skipped-method set"));
        }
    }

    public static SkippedMethodGateEvidence notAnalyzed() {
        return new SkippedMethodGateEvidence(
                List.of(),
                SkippedMethodGateDecision.NOT_ANALYZED);
    }
}
