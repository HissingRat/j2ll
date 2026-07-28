package xyz.melodysky.protection.audit;

import java.util.List;
import java.util.Objects;

/** Stable hash-only protection coverage snapshot for one build. */
public record ProtectionCoverageSnapshot(
        int evaluatedFacts,
        int requestedFacts,
        int applicableFacts,
        int notApplicableFacts,
        int unknownApplicabilityFacts,
        int affectedFacts,
        int affectedRateBasisPoints,
        List<ProtectionPassCoverageRow> passes,
        List<ProtectionPassCoverageFact> facts) {
    public ProtectionCoverageSnapshot {
        if (evaluatedFacts < 0
                || requestedFacts < 0
                || applicableFacts < 0
                || notApplicableFacts < 0
                || unknownApplicabilityFacts < 0
                || affectedFacts < 0
                || affectedRateBasisPoints < 0
                || affectedRateBasisPoints > 10_000
                || requestedFacts > evaluatedFacts
                || affectedFacts > requestedFacts
                || affectedFacts > applicableFacts
                || applicableFacts + notApplicableFacts
                                + unknownApplicabilityFacts
                        != evaluatedFacts) {
            throw new IllegalArgumentException(
                    "protection coverage snapshot counts are invalid");
        }
        passes = Objects.requireNonNull(passes, "passes")
                .stream()
                .sorted()
                .toList();
        facts = Objects.requireNonNull(facts, "facts")
                .stream()
                .sorted()
                .toList();
        if (facts.size() != evaluatedFacts) {
            throw new IllegalArgumentException(
                    "evaluatedFacts must equal fact rows");
        }
        long uniqueFacts = facts.stream()
                .map(ProtectionPassCoverageFact::factKey)
                .distinct()
                .count();
        if (uniqueFacts != evaluatedFacts) {
            throw new IllegalArgumentException(
                    "protection coverage facts must be unique by pass and subject");
        }
    }
}
