package xyz.melodysky.protection.audit;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import xyz.melodysky.config.ProtectionSeedMode;

/** Enforces carrier diversity for random builds and equality for seeded builds. */
public final class BusinessStringCarrierReuseAudit {
    public static final String RANDOMIZED_DIVERSIFIED =
            "RANDOMIZED_BUSINESS_STRING_CARRIERS_DIVERSIFIED";
    public static final String RANDOMIZED_REUSE_DETECTED =
            "RANDOMIZED_BUSINESS_STRING_CARRIER_REUSE_DETECTED";
    public static final String REPRODUCIBLE_MATCHED =
            "REPRODUCIBLE_BUSINESS_STRING_CARRIERS_MATCHED";
    public static final String REPRODUCIBLE_CHANGED =
            "REPRODUCIBLE_BUSINESS_STRING_CARRIERS_CHANGED";
    public static final String EMPTY_COMPARISON =
            "BUSINESS_STRING_CARRIER_REUSE_NOT_APPLICABLE";

    public BusinessStringCarrierReuseMetric compare(
            ProtectionSeedMode seedMode,
            BusinessStringCarrierSnapshot first,
            BusinessStringCarrierSnapshot second) {
        Objects.requireNonNull(seedMode, "seedMode");
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        int commonNames = intersectionSize(
                first.carrierNameIdentityHashes(),
                second.carrierNameIdentityHashes());
        int commonTokens = intersectionSize(
                first.numericTokenIdentityHashes(),
                second.numericTokenIdentityHashes());
        int reuseBasisPoints = reuseBasisPoints(
                first,
                second,
                commonNames,
                commonTokens);

        boolean bothNonEmpty =
                first.carrierCount() > 0 && second.carrierCount() > 0;
        boolean passed;
        String reasonCode;
        if (seedMode == ProtectionSeedMode.RANDOMIZED) {
            passed = !bothNonEmpty
                    || commonNames == 0 && commonTokens == 0;
            reasonCode = !bothNonEmpty
                    ? EMPTY_COMPARISON
                    : passed
                            ? RANDOMIZED_DIVERSIFIED
                            : RANDOMIZED_REUSE_DETECTED;
        } else {
            passed = first.carrierCount() == second.carrierCount()
                    && first.carrierNameIdentityHashes()
                            .equals(second.carrierNameIdentityHashes())
                    && first.numericTokenIdentityHashes()
                            .equals(second.numericTokenIdentityHashes());
            reasonCode = passed
                    ? REPRODUCIBLE_MATCHED
                    : REPRODUCIBLE_CHANGED;
        }
        return new BusinessStringCarrierReuseMetric(
                seedMode,
                first.carrierCount(),
                second.carrierCount(),
                commonNames,
                commonTokens,
                reuseBasisPoints,
                passed,
                reasonCode);
    }

    private int intersectionSize(List<String> first, List<String> second) {
        Set<String> common = new HashSet<>(first);
        common.retainAll(second);
        return common.size();
    }

    private int reuseBasisPoints(
            BusinessStringCarrierSnapshot first,
            BusinessStringCarrierSnapshot second,
            int commonNames,
            int commonTokens) {
        int comparableNames = Math.min(
                first.carrierNameIdentityHashes().size(),
                second.carrierNameIdentityHashes().size());
        int comparableTokens = Math.min(
                first.numericTokenIdentityHashes().size(),
                second.numericTokenIdentityHashes().size());
        int denominator = comparableNames + comparableTokens;
        return denominator == 0
                ? 0
                : (int) ((long) (commonNames + commonTokens)
                        * 10_000
                        / denominator);
    }
}
