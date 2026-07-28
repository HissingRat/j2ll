package xyz.melodysky.protection.audit;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Aggregated requested/applicable/affected/status/reason counts for one pass. */
public record ProtectionPassCoverageRow(
        String layer,
        String passName,
        int evaluatedSubjects,
        int requestedSubjects,
        int applicableSubjects,
        int notApplicableSubjects,
        int unknownApplicabilitySubjects,
        int affectedSubjects,
        int affectedRateBasisPoints,
        Map<String, Integer> statusCounts,
        Map<String, Integer> reasonCounts)
        implements Comparable<ProtectionPassCoverageRow> {
    public ProtectionPassCoverageRow {
        Objects.requireNonNull(layer, "layer");
        Objects.requireNonNull(passName, "passName");
        if (layer.isBlank()
                || passName.isBlank()
                || evaluatedSubjects < 0
                || requestedSubjects < 0
                || applicableSubjects < 0
                || notApplicableSubjects < 0
                || unknownApplicabilitySubjects < 0
                || affectedSubjects < 0
                || affectedRateBasisPoints < 0
                || affectedRateBasisPoints > 10_000
                || requestedSubjects > evaluatedSubjects
                || affectedSubjects > requestedSubjects
                || affectedSubjects > applicableSubjects
                || applicableSubjects
                                + notApplicableSubjects
                                + unknownApplicabilitySubjects
                        != evaluatedSubjects) {
            throw new IllegalArgumentException(
                    "protection pass coverage row is invalid");
        }
        statusCounts = immutableCounts(statusCounts, "statusCounts");
        reasonCounts = immutableCounts(reasonCounts, "reasonCounts");
        if (sum(statusCounts) != evaluatedSubjects
                || sum(reasonCounts) != evaluatedSubjects) {
            throw new IllegalArgumentException(
                    "status/reason counts must cover every evaluated subject");
        }
    }

    public String passKey() {
        return layer + "\0" + passName;
    }

    @Override
    public int compareTo(ProtectionPassCoverageRow other) {
        int byLayer = layer.compareTo(other.layer);
        return byLayer != 0
                ? byLayer
                : passName.compareTo(other.passName);
    }

    private static Map<String, Integer> immutableCounts(
            Map<String, Integer> values,
            String name) {
        Objects.requireNonNull(values, name);
        TreeMap<String, Integer> sorted = new TreeMap<>();
        values.forEach((key, value) -> {
            Objects.requireNonNull(key, name + " key");
            Objects.requireNonNull(value, name + " value");
            if (key.isBlank() || value < 0) {
                throw new IllegalArgumentException(
                        name + " contains an invalid count");
            }
            sorted.put(key, value);
        });
        return Collections.unmodifiableMap(sorted);
    }

    private static int sum(Map<String, Integer> values) {
        return values.values().stream()
                .mapToInt(Integer::intValue)
                .sum();
    }
}
