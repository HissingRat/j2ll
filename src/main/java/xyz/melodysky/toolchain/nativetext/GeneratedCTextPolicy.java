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
        if (lifetime == NativeTextLifetimePolicy.LOW_SENSITIVITY_LAZY_ONCE
                && purpose != NativeTextPurpose.RUNTIME_ERROR) {
            throw new IllegalArgumentException(
                    "lazy-once native text is restricted to the runtime-error domain");
        }
    }

    public static GeneratedCTextPolicy sensitive(
            NativeTextPurpose purpose) {
        return new GeneratedCTextPolicy(
                purpose,
                NativeTextLifetimePolicy.CALL_LOCAL_SCRATCH);
    }

    public static GeneratedCTextPolicy lowSensitivityRuntimeError() {
        return new GeneratedCTextPolicy(
                NativeTextPurpose.RUNTIME_ERROR,
                NativeTextLifetimePolicy.LOW_SENSITIVITY_LAZY_ONCE);
    }
}
