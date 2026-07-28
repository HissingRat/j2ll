package xyz.melodysky.protection.audit;

import java.util.Objects;
import xyz.melodysky.config.ProtectionSeedMode;

public final class DualBuildFingerprintAudit {
    public static final String RANDOMIZED_BUILD_CHANGED =
            "RANDOMIZED_BUILD_FINGERPRINT_CHANGED";
    public static final String RANDOMIZED_BUILD_REUSED =
            "RANDOMIZED_BUILD_SOURCE_FINGERPRINT_REUSED";
    public static final String REPRODUCIBLE_BUILD_MATCHED =
            "REPRODUCIBLE_BUILD_FINGERPRINT_MATCHED";
    public static final String REPRODUCIBLE_SOURCE_CHANGED =
            "REPRODUCIBLE_BUILD_SOURCE_FINGERPRINT_CHANGED";
    public static final String REPRODUCIBLE_NATIVE_CHANGED =
            "REPRODUCIBLE_BUILD_NATIVE_FINGERPRINT_CHANGED";

    public DualBuildFingerprintResult compare(
            ProtectionSeedMode seedMode,
            BuildArtifactFingerprint first,
            BuildArtifactFingerprint second) {
        Objects.requireNonNull(seedMode, "seedMode");
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        boolean nativeChanged =
                !first.nativeSha256().equals(second.nativeSha256());
        boolean generatedCChanged =
                !first.generatedCSha256().equals(second.generatedCSha256());
        boolean combinedChanged =
                !first.combinedSha256().equals(second.combinedSha256());
        boolean passed = seedMode == ProtectionSeedMode.RANDOMIZED
                ? generatedCChanged
                : !generatedCChanged && !nativeChanged;
        String reasonCode;
        if (seedMode == ProtectionSeedMode.RANDOMIZED) {
            reasonCode = passed
                    ? RANDOMIZED_BUILD_CHANGED
                    : RANDOMIZED_BUILD_REUSED;
        } else if (generatedCChanged) {
            reasonCode = REPRODUCIBLE_SOURCE_CHANGED;
        } else if (nativeChanged) {
            reasonCode = REPRODUCIBLE_NATIVE_CHANGED;
        } else {
            reasonCode = REPRODUCIBLE_BUILD_MATCHED;
        }
        return new DualBuildFingerprintResult(
                seedMode,
                nativeChanged,
                generatedCChanged,
                combinedChanged,
                first.nativeSizeBytes(),
                second.nativeSizeBytes(),
                second.nativeSizeBytes() - first.nativeSizeBytes(),
                first.generatedCSizeBytes(),
                second.generatedCSizeBytes(),
                second.generatedCSizeBytes()
                        - first.generatedCSizeBytes(),
                passed,
                reasonCode);
    }
}
