package xyz.melodysky.toolchain;

import java.io.IOException;

/** Binds the three route recipes to optimized-machine continuation evidence. */
final class NativeRegistrationRouteAssemblyShapeVerifier {
    void verify(
            NativeRegistrationPostCallRecipe recipe,
            NativeRegistrationAssemblyInstructionSet.ContinuationProfile profile,
            String symbol) throws IOException {
        boolean valid = switch (recipe) {
            case XOR_JINT -> has(profile, 32, 64)
                    && profile.memoryOperations() >= 4;
            case ADD_JLONG -> has(profile, 32, 64)
                    && profile.memoryOperations() >= 6
                    && profile.shiftOrRotate();
            case MIRROR_JINT -> has(profile, 32, 64)
                    && profile.memoryOperations() >= 8;
        };
        if (!valid) {
            throw NativeRegistrationAssemblyIndex.failure(
                    "ROUTE_RECIPE_MACHINE_SHAPE",
                    symbol + ":" + recipe);
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
