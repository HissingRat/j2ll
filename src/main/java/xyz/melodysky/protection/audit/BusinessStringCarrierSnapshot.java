package xyz.melodysky.protection.audit;

import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Hash-only business-string carrier evidence from one debug LLVM module.
 *
 * <p>The carrier names and signed numeric tokens are deliberately unavailable
 * through this API. Only domain-separated SHA-256 identities are retained.
 */
public record BusinessStringCarrierSnapshot(
        int carrierCount,
        List<String> carrierNameIdentityHashes,
        List<String> numericTokenIdentityHashes) {
    public BusinessStringCarrierSnapshot {
        if (carrierCount < 0) {
            throw new IllegalArgumentException(
                    "business-string carrier count must not be negative");
        }
        carrierNameIdentityHashes = hashes(
                carrierNameIdentityHashes,
                "carrierNameIdentityHashes");
        numericTokenIdentityHashes = hashes(
                numericTokenIdentityHashes,
                "numericTokenIdentityHashes");
        if (carrierNameIdentityHashes.size() != carrierCount) {
            throw new IllegalArgumentException(
                    "business-string carrier names must be unique");
        }
        if (numericTokenIdentityHashes.size() > carrierCount) {
            throw new IllegalArgumentException(
                    "business-string numeric-token count exceeds carrier count");
        }
        if (carrierCount > 0 && numericTokenIdentityHashes.isEmpty()) {
            throw new IllegalArgumentException(
                    "business-string carriers require numeric-token evidence");
        }
    }

    private static List<String> hashes(List<String> values, String name) {
        Objects.requireNonNull(values, name);
        TreeSet<String> sorted = new TreeSet<>();
        for (String value : values) {
            sorted.add(HashOnlyEvidence.requireSha256(value, name));
        }
        return List.copyOf(sorted);
    }
}
