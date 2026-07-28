package xyz.melodysky.protection.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import xyz.melodysky.config.ProtectionSeedMode;

class DualBuildFingerprintAuditTest {
    private final DualBuildFingerprintAudit audit =
            new DualBuildFingerprintAudit();

    @Test
    void randomizedBuildMustChangeGeneratedSourceNotOnlyNativeNoise() {
        BuildArtifactFingerprint first = fingerprint("native-a", "source-a");
        BuildArtifactFingerprint changed = fingerprint("native-b", "source-b");
        BuildArtifactFingerprint linkerNoiseOnly = fingerprint("native-b", "source-a");

        DualBuildFingerprintResult passed = audit.compare(
                ProtectionSeedMode.RANDOMIZED,
                first,
                changed);
        DualBuildFingerprintResult failed = audit.compare(
                ProtectionSeedMode.RANDOMIZED,
                first,
                linkerNoiseOnly);

        assertTrue(passed.passed());
        assertTrue(passed.nativeChanged());
        assertTrue(passed.generatedCChanged());
        assertEquals(
                "native-b".getBytes(StandardCharsets.UTF_8).length
                        - "native-a".getBytes(StandardCharsets.UTF_8).length,
                passed.nativeSizeDeltaBytes());
        assertEquals(
                "source-b".getBytes(StandardCharsets.UTF_8).length
                        - "source-a".getBytes(StandardCharsets.UTF_8).length,
                passed.generatedCSizeDeltaBytes());
        assertEquals(
                DualBuildFingerprintAudit.RANDOMIZED_BUILD_CHANGED,
                passed.reasonCode());
        assertFalse(failed.passed());
        assertTrue(failed.nativeChanged());
        assertFalse(failed.generatedCChanged());
        assertEquals(
                DualBuildFingerprintAudit.RANDOMIZED_BUILD_REUSED,
                failed.reasonCode());
    }

    @Test
    void explicitSeedRequiresExactNativeAndSourceFingerprintMatch() {
        BuildArtifactFingerprint first = fingerprint("native-a", "source-a");
        BuildArtifactFingerprint changed = fingerprint("native-a", "source-b");

        DualBuildFingerprintResult passed = audit.compare(
                ProtectionSeedMode.REPRODUCIBLE,
                first,
                first);
        DualBuildFingerprintResult failed = audit.compare(
                ProtectionSeedMode.REPRODUCIBLE,
                first,
                changed);
        String json = new DualBuildFingerprintReportWriter().json(passed);

        assertTrue(passed.passed());
        assertFalse(passed.combinedChanged());
        assertEquals(
                DualBuildFingerprintAudit.REPRODUCIBLE_BUILD_MATCHED,
                passed.reasonCode());
        assertFalse(failed.passed());
        assertTrue(failed.generatedCChanged());
        assertEquals(
                DualBuildFingerprintAudit.REPRODUCIBLE_SOURCE_CHANGED,
                failed.reasonCode());
        DualBuildFingerprintResult nativeTimestampDifference = audit.compare(
                ProtectionSeedMode.REPRODUCIBLE,
                first,
                fingerprint("native-b", "source-a"));
        assertFalse(nativeTimestampDifference.passed());
        assertEquals(
                DualBuildFingerprintAudit.REPRODUCIBLE_NATIVE_CHANGED,
                nativeTimestampDifference.reasonCode());
        assertTrue(json.contains("\"seedMode\": \"reproducible\""));
        assertTrue(json.contains("\"artifactSizeEvidence\""));
        assertTrue(json.contains("\"nativeSizeDeltaBytes\": 0"));
        assertTrue(json.contains("\"passed\": true"));
        assertFalse(json.contains(first.combinedSha256()));
    }

    private BuildArtifactFingerprint fingerprint(
            String nativeText,
            String generatedCText) {
        return BuildArtifactFingerprint.of(
                nativeText.getBytes(StandardCharsets.UTF_8),
                generatedCText.getBytes(StandardCharsets.UTF_8));
    }
}
