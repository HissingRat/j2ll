package xyz.melodysky.protection.audit;

import java.util.Objects;
import xyz.melodysky.config.ProtectionSeedMode;

/** Aggregate-only cross-build business-string carrier reuse evidence. */
public record BusinessStringCarrierReuseMetric(
        ProtectionSeedMode seedMode,
        int firstCarrierCount,
        int secondCarrierCount,
        int commonNameCount,
        int commonNumericTokenCount,
        int reuseRateBasisPoints,
        boolean passed,
        String reasonCode) {
    public BusinessStringCarrierReuseMetric {
        Objects.requireNonNull(seedMode, "seedMode");
        Objects.requireNonNull(reasonCode, "reasonCode");
        if (firstCarrierCount < 0
                || secondCarrierCount < 0
                || commonNameCount < 0
                || commonNameCount
                        > Math.min(firstCarrierCount, secondCarrierCount)
                || commonNumericTokenCount < 0
                || commonNumericTokenCount
                        > Math.min(firstCarrierCount, secondCarrierCount)
                || reuseRateBasisPoints < 0
                || reuseRateBasisPoints > 10_000) {
            throw new IllegalArgumentException(
                    "business-string carrier reuse metric is invalid");
        }
        if (reasonCode.isBlank()) {
            throw new IllegalArgumentException(
                    "business-string carrier reuse reason code must not be blank");
        }
    }
}
