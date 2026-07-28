package xyz.melodysky.toolchain.nativetext;

/**
 * Structural native-text metrics extracted from generated C.
 */
public record NativeTextSourceMetrics(
        int cipherArrayCount,
        int runtimeBoundCipherReadCount,
        int siteBoundCodecCount,
        int codecFamilyCount,
        int decoderFunctionCount,
        int largestDecoderCipherFanout,
        int fixedDecoderShapeOccurrences,
        int adjacentSeedCipherOccurrences,
        int firstMissingRuntimeBoundCipherOffset,
        int firstDecoderFanoutOffset,
        int firstFixedDecoderShapeOffset,
        int firstAdjacentSeedCipherOffset) {
    public NativeTextSourceMetrics {
        if (cipherArrayCount < 0
                || runtimeBoundCipherReadCount < 0
                || runtimeBoundCipherReadCount > cipherArrayCount
                || siteBoundCodecCount < 0
                || codecFamilyCount < 0
                || decoderFunctionCount < 0
                || largestDecoderCipherFanout < 0
                || fixedDecoderShapeOccurrences < 0
                || adjacentSeedCipherOccurrences < 0
                || firstMissingRuntimeBoundCipherOffset < -1
                || firstDecoderFanoutOffset < -1
                || firstFixedDecoderShapeOffset < -1
                || firstAdjacentSeedCipherOffset < -1) {
            throw new IllegalArgumentException(
                    "native-text source metrics are invalid");
        }
    }
}
