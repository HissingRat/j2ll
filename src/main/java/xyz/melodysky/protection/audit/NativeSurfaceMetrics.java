package xyz.melodysky.protection.audit;

import java.util.List;
import java.util.Objects;

record NativeSurfaceMetrics(
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
        List<SensitivePlaintextMetric> sensitivePlaintextMetrics) {
    NativeSurfaceMetrics {
        if (fallbackCarrierOccurrences < 0
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
            throw new IllegalArgumentException("native surface counts must be non-negative");
        }
        sensitivePlaintextMetrics = Objects.requireNonNull(
                        sensitivePlaintextMetrics,
                        "sensitivePlaintextMetrics")
                .stream()
                .sorted()
                .toList();
    }
}
