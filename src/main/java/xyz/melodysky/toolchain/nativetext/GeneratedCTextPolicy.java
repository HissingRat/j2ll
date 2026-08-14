package xyz.melodysky.toolchain.nativetext;

import java.util.Objects;

/**
 * Explicit domain and plaintext-lifetime policy for generated C text.
 */
public record GeneratedCTextPolicy(
        NativeTextPurpose purpose,
        NativeTextLifetimePolicy lifetime) {
    public GeneratedCTextPolicy {
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(lifetime, "lifetime");
    }

    public static GeneratedCTextPolicy sensitive(
            NativeTextPurpose purpose) {
        return new GeneratedCTextPolicy(
                purpose,
                NativeTextLifetimePolicy.CALL_LOCAL_SCRATCH);
    }
}
