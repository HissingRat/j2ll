package xyz.melodysky.protection.audit;

import java.util.List;
import java.util.Objects;

/** Cross-build requested/applicable/affected/status/reason coverage diff. */
public record ProtectionCoverageDiffMetric(
        int firstFactCount,
        int secondFactCount,
        int commonFactCount,
        int addedFactCount,
        int removedFactCount,
        int requestedChangedCount,
        int applicabilityChangedCount,
        int affectedChangedCount,
        int statusChangedCount,
        int reasonChangedCount,
        int firstAffectedFacts,
        int secondAffectedFacts,
        int affectedDelta,
        List<ProtectionCoverageDiffRow> passes,
        String reasonCode) {
    public ProtectionCoverageDiffMetric {
        if (firstFactCount < 0
                || secondFactCount < 0
                || commonFactCount < 0
                || addedFactCount < 0
                || removedFactCount < 0
                || requestedChangedCount < 0
                || applicabilityChangedCount < 0
                || affectedChangedCount < 0
                || statusChangedCount < 0
                || reasonChangedCount < 0
                || firstAffectedFacts < 0
                || secondAffectedFacts < 0
                || affectedDelta != secondAffectedFacts - firstAffectedFacts) {
            throw new IllegalArgumentException(
                    "protection coverage diff counts are invalid");
        }
        passes = Objects.requireNonNull(passes, "passes")
                .stream()
                .sorted()
                .toList();
        Objects.requireNonNull(reasonCode, "reasonCode");
        if (reasonCode.isBlank()) {
            throw new IllegalArgumentException(
                    "protection coverage diff reason code must not be blank");
        }
    }
}
