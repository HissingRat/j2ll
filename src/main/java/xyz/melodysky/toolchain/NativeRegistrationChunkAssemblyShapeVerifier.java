package xyz.melodysky.toolchain;

import java.io.IOException;

/** Binds each planned closed chunk variant to minimum optimized-machine width/shape evidence. */
final class NativeRegistrationChunkAssemblyShapeVerifier {
    void verify(
            NativeRegistrationChunkPostCallVariant variant,
            NativeRegistrationAssemblyInstructionSet.ContinuationProfile profile,
            String symbol) throws IOException {
        boolean valid = !profile.signature().isEmpty()
                && switch (variant) {
                    case JINT_U16_FOLD -> has(profile, 16, 32);
                    case JLONG_U32_FOLD -> has(profile, 32, 64);
                    case JINT_DUAL_WORD -> has(profile, 32, 64)
                            && profile.memoryOperations() >= 4;
                    case JLONG_ORBIT -> has(profile, 32, 64)
                            && profile.shiftOrRotate();
                    case JINT_MIXED_WIDTH -> has(profile, 8, 32, 64);
                    case JLONG_MIXED_WIDTH -> has(profile, 16, 32, 64);
                    case JINT_SIGNED_BRAID -> has(profile, 32, 64)
                            && profile.memoryOperations() >= 5;
                    case JLONG_SPLIT_WORD -> has(profile, 32, 64)
                            && profile.memoryOperations() >= 6;
                };
        if (!valid) {
            throw NativeRegistrationAssemblyIndex.failure(
                    "CHUNK_VARIANT_MACHINE_SHAPE",
                    symbol + ":" + variant);
        }
    }

    private boolean has(
            NativeRegistrationAssemblyInstructionSet.ContinuationProfile profile,
            int... widths) {
        for (int width : widths) {
            if (!profile.widths().contains(width)) {
                return false;
            }
        }
        return true;
    }
}
