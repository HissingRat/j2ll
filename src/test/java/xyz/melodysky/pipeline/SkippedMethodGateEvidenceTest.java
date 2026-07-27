package xyz.melodysky.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.melodysky.diagnostic.DiagnosticStage;

class SkippedMethodGateEvidenceTest {
    private static final SkippedMethod ALPHA = skipped("pkg/Alpha");
    private static final SkippedMethod ZETA = skipped("pkg/Zeta");

    @Test
    void sortsMethodsAndRejectsDecisionListMismatches() {
        SkippedMethodGateEvidence evidence = new SkippedMethodGateEvidence(
                List.of(ZETA, ALPHA),
                SkippedMethodGateDecision.APPROVED);

        assertEquals(List.of(ALPHA, ZETA), evidence.methods());
        assertThrows(
                IllegalArgumentException.class,
                () -> new SkippedMethodGateEvidence(
                        List.of(),
                        SkippedMethodGateDecision.APPROVED));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SkippedMethodGateEvidence(
                        List.of(ALPHA),
                        SkippedMethodGateDecision.NOT_REQUIRED));
    }

    private static SkippedMethod skipped(String owner) {
        return new SkippedMethod(
                owner,
                "method",
                "()V",
                DiagnosticStage.LOWERING,
                "UNSUPPORTED",
                "unsupported");
    }
}
