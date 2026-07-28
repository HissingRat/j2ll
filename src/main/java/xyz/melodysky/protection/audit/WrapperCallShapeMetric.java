package xyz.melodysky.protection.audit;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Per-build wrapper call-shape counts with explicit evidence provenance. */
public record WrapperCallShapeMetric(
        int wrapperCount,
        int resolvedWrapperCount,
        int directSingleCalleeCount,
        int indirectWrapperCount,
        int multipleCalleeCount,
        int unresolvedWrapperCount,
        boolean finalBinaryEvidence,
        Map<String, Integer> evidenceKindCounts,
        List<WrapperCallEvidence> wrappers) {
    public WrapperCallShapeMetric {
        if (wrapperCount < 0
                || resolvedWrapperCount < 0
                || directSingleCalleeCount < 0
                || indirectWrapperCount < 0
                || multipleCalleeCount < 0
                || unresolvedWrapperCount < 0
                || resolvedWrapperCount + unresolvedWrapperCount
                        != wrapperCount
                || directSingleCalleeCount
                                + indirectWrapperCount
                                + multipleCalleeCount
                                + unresolvedWrapperCount
                        != wrapperCount) {
            throw new IllegalArgumentException(
                    "wrapper call-shape counts are invalid");
        }
        evidenceKindCounts = immutableCounts(
                evidenceKindCounts,
                "evidenceKindCounts");
        wrappers = Objects.requireNonNull(wrappers, "wrappers")
                .stream()
                .sorted()
                .toList();
        if (wrappers.size() != wrapperCount) {
            throw new IllegalArgumentException(
                    "wrapperCount must equal wrapper evidence rows");
        }
        long uniqueBindings = wrappers.stream()
                .map(WrapperCallEvidence::bindingIdentityHash)
                .distinct()
                .count();
        int evidenceKinds = evidenceKindCounts.values().stream()
                .mapToInt(Integer::intValue)
                .sum();
        if (uniqueBindings != wrapperCount || evidenceKinds != wrapperCount) {
            throw new IllegalArgumentException(
                    "wrapper evidence must have unique bindings and complete provenance counts");
        }
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
                throw new IllegalArgumentException(name + " contains an invalid count");
            }
            sorted.put(key, value);
        });
        return java.util.Collections.unmodifiableMap(sorted);
    }
}
