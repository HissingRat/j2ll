package xyz.melodysky.pipeline;

import java.io.IOException;
import java.util.List;

/**
 * Invocation-level policy boundary for accepting selected methods that remain
 * as Java bytecode.
 */
@FunctionalInterface
public interface SkippedMethodApproval {
    boolean approve(List<SkippedMethod> skippedMethods) throws IOException;

    default void onEvaluated(SkippedMethodGateEvidence evidence) {
        // Optional observation hook for CLI failure-report preservation.
    }

    static SkippedMethodApproval rejectAll() {
        return ignored -> false;
    }

    static SkippedMethodApproval allowAll() {
        return ignored -> true;
    }
}
