package xyz.melodysky.report;

import java.util.List;
import java.util.Objects;

public record ArtifactAuditResult(
        boolean passed,
        List<ArtifactAuditCheck> checks,
        List<SensitivePlaintextFact> checkedSensitiveFacts,
        List<SensitivePlaintextFact> observedOnlySensitiveFacts,
        List<SensitivePlaintextFact> skippedSensitiveFacts) {
    public ArtifactAuditResult(boolean passed, List<ArtifactAuditCheck> checks) {
        this(passed, checks, List.of(), List.of(), List.of());
    }

    public ArtifactAuditResult(
            boolean passed,
            List<ArtifactAuditCheck> checks,
            List<SensitivePlaintextFact> checkedSensitiveFacts,
            List<SensitivePlaintextFact> observedOnlySensitiveFacts,
            List<SensitivePlaintextFact> skippedSensitiveFacts) {
        this.passed = passed;
        this.checks = List.copyOf(Objects.requireNonNull(checks, "checks"));
        this.checkedSensitiveFacts = copyFacts(checkedSensitiveFacts);
        this.observedOnlySensitiveFacts = copyFacts(observedOnlySensitiveFacts);
        this.skippedSensitiveFacts = copyFacts(skippedSensitiveFacts);
    }

    private static List<SensitivePlaintextFact> copyFacts(List<SensitivePlaintextFact> facts) {
        return facts.stream()
                .filter(Objects::nonNull)
                .sorted(java.util.Comparator
                        .comparing(SensitivePlaintextFact::literalHash)
                        .thenComparing(SensitivePlaintextFact::sourceMethod)
                        .thenComparing(SensitivePlaintextFact::pathKind)
                        .thenComparing(SensitivePlaintextFact::gateMode)
                        .thenComparing(SensitivePlaintextFact::promotionReason))
                .toList();
    }
}
