package xyz.melodysky.protection.audit;

import java.util.List;
import java.util.Objects;

/** Cross-build wrapper mapping reuse result. All binding identities are hashes. */
public record WrapperMappingReuseMetric(
        int firstWrapperCount,
        int secondWrapperCount,
        int commonBindingCount,
        int reusableMappingCount,
        int reuseRateBasisPoints,
        int shapeChangedCount,
        int resolutionChangedCount,
        int unresolvedCommonCount,
        boolean finalBinaryEvidence,
        List<String> reusableBindingHashes,
        List<String> shapeChangedBindingHashes,
        List<String> resolutionChangedBindingHashes,
        List<String> unresolvedBindingHashes,
        List<String> addedBindingHashes,
        List<String> removedBindingHashes,
        String reasonCode) {
    public WrapperMappingReuseMetric {
        if (firstWrapperCount < 0
                || secondWrapperCount < 0
                || commonBindingCount < 0
                || reusableMappingCount < 0
                || reuseRateBasisPoints < 0
                || reuseRateBasisPoints > 10_000
                || shapeChangedCount < 0
                || resolutionChangedCount < 0
                || unresolvedCommonCount < 0) {
            throw new IllegalArgumentException(
                    "wrapper mapping reuse counts are invalid");
        }
        reusableBindingHashes = hashes(
                reusableBindingHashes,
                "reusableBindingHashes");
        shapeChangedBindingHashes = hashes(
                shapeChangedBindingHashes,
                "shapeChangedBindingHashes");
        resolutionChangedBindingHashes = hashes(
                resolutionChangedBindingHashes,
                "resolutionChangedBindingHashes");
        unresolvedBindingHashes = hashes(
                unresolvedBindingHashes,
                "unresolvedBindingHashes");
        addedBindingHashes = hashes(addedBindingHashes, "addedBindingHashes");
        removedBindingHashes = hashes(
                removedBindingHashes,
                "removedBindingHashes");
        Objects.requireNonNull(reasonCode, "reasonCode");
        if (reasonCode.isBlank()) {
            throw new IllegalArgumentException(
                    "wrapper mapping reuse reason code must not be blank");
        }
    }

    private static List<String> hashes(List<String> values, String name) {
        return Objects.requireNonNull(values, name)
                .stream()
                .map(value -> HashOnlyEvidence.requireSha256(value, name))
                .sorted()
                .distinct()
                .toList();
    }
}
