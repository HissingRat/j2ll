package xyz.melodysky.protection.audit;

import java.util.List;
import java.util.Objects;

public record AttackerAuditMetrics(
        String target,
        String nativeSha256,
        String generatedCSha256,
        long nativeSizeBytes,
        long generatedCSizeBytes,
        int fallbackCarrierOccurrences,
        int classMagicOccurrences,
        int legacyGlobalMetadataOccurrences,
        int legacyDecodeAllOccurrences,
        int nativePrintableStringCount,
        int generatedCStringLiteralCount,
        int generatedNativeTextCipherArrayCount,
        int generatedNativeTextSiteCodecCount,
        int generatedNativeTextCodecFamilyCount,
        int generatedNativeTextDecoderCount,
        int generatedNativeTextLargestDecoderFanout,
        int generatedNativeTextFixedShapeOccurrences,
        int generatedNativeTextAdjacentSeedCipherOccurrences,
        List<SensitivePlaintextMetric> sensitivePlaintextMetrics,
        List<String> generatedCHardeningFindings,
        List<String> generatedCHardeningEvidence,
        List<String> dynamicExports,
        List<String> unexpectedExports,
        List<String> missingExports,
        boolean passed) {
    public AttackerAuditMetrics {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(nativeSha256, "nativeSha256");
        Objects.requireNonNull(generatedCSha256, "generatedCSha256");
        if (target.isBlank()
                || nativeSha256.isBlank()
                || generatedCSha256.isBlank()
                || nativeSizeBytes < 0
                || generatedCSizeBytes < 0
                || fallbackCarrierOccurrences < 0
                || classMagicOccurrences < 0
                || legacyGlobalMetadataOccurrences < 0
                || legacyDecodeAllOccurrences < 0
                || nativePrintableStringCount < 0
                || generatedCStringLiteralCount < 0
                || generatedNativeTextCipherArrayCount < 0
                || generatedNativeTextSiteCodecCount < 0
                || generatedNativeTextCodecFamilyCount < 0
                || generatedNativeTextDecoderCount < 0
                || generatedNativeTextLargestDecoderFanout < 0
                || generatedNativeTextFixedShapeOccurrences < 0
                || generatedNativeTextAdjacentSeedCipherOccurrences < 0) {
            throw new IllegalArgumentException("attacker audit metrics are invalid");
        }
        sensitivePlaintextMetrics = sortedMetrics(sensitivePlaintextMetrics);
        generatedCHardeningFindings = sorted(generatedCHardeningFindings);
        generatedCHardeningEvidence = sorted(generatedCHardeningEvidence);
        dynamicExports = sorted(dynamicExports);
        unexpectedExports = sorted(unexpectedExports);
        missingExports = sorted(missingExports);
    }

    public int sensitivePlaintextOccurrences() {
        return sensitivePlaintextMetrics.stream()
                .mapToInt(SensitivePlaintextMetric::totalOccurrences)
                .sum();
    }

    private static List<SensitivePlaintextMetric> sortedMetrics(
            List<SensitivePlaintextMetric> values) {
        return Objects.requireNonNull(values, "sensitivePlaintextMetrics")
                .stream()
                .sorted()
                .toList();
    }

    private static List<String> sorted(List<String> values) {
        return Objects.requireNonNull(values, "values")
                .stream()
                .filter(Objects::nonNull)
                .sorted()
                .distinct()
                .toList();
    }
}
