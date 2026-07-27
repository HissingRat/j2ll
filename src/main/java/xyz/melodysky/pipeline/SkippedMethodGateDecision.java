package xyz.melodysky.pipeline;

import java.util.Objects;

/** Stable report value for the invocation-level skipped-method confirmation gate. */
public enum SkippedMethodGateDecision {
    NOT_ANALYZED("notAnalyzed"),
    NOT_REQUIRED("notRequired"),
    APPROVED("approved"),
    REJECTED("rejected"),
    INPUT_ERROR("inputError"),
    NOT_EVALUATED_PRIOR_FAILURE("notEvaluatedPriorFailure");

    private final String wireName;

    SkippedMethodGateDecision(String wireName) {
        this.wireName = Objects.requireNonNull(wireName, "wireName");
    }

    public String wireName() {
        return wireName;
    }
}
