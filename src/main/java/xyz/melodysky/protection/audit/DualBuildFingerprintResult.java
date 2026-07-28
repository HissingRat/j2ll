package xyz.melodysky.protection.audit;

import java.util.Objects;
import xyz.melodysky.config.ProtectionSeedMode;

public record DualBuildFingerprintResult(
        ProtectionSeedMode seedMode,
        boolean nativeChanged,
        boolean generatedCChanged,
        boolean combinedChanged,
        long firstNativeSizeBytes,
        long secondNativeSizeBytes,
        long nativeSizeDeltaBytes,
        long firstGeneratedCSizeBytes,
        long secondGeneratedCSizeBytes,
        long generatedCSizeDeltaBytes,
        boolean passed,
        String reasonCode) {
    public DualBuildFingerprintResult {
        Objects.requireNonNull(seedMode, "seedMode");
        Objects.requireNonNull(reasonCode, "reasonCode");
        if (reasonCode.isBlank()) {
            throw new IllegalArgumentException(
                    "dual-build fingerprint reason code must not be blank");
        }
        if (firstNativeSizeBytes < 0
                || secondNativeSizeBytes < 0
                || firstGeneratedCSizeBytes < 0
                || secondGeneratedCSizeBytes < 0
                || nativeSizeDeltaBytes
                        != secondNativeSizeBytes - firstNativeSizeBytes
                || generatedCSizeDeltaBytes
                        != secondGeneratedCSizeBytes
                                - firstGeneratedCSizeBytes) {
            throw new IllegalArgumentException(
                    "dual-build fingerprint size evidence is invalid");
        }
    }
}
