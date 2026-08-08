package xyz.melodysky.toolchain;

import java.util.Objects;

/** Planned per-target result across generated C, modeled LLVM and opaque objects. */
public record NativeLlvmUnwindTargetSummary(
        NativeUnwindRetentionDecision generatedCDecision,
        int moduleCount,
        int omittedModuleCount,
        int retainedModuleCount,
        int unmodeledObjectInputCount,
        boolean finalOmissionExpected,
        boolean effectiveRetention,
        NativeUnwindRetentionReason reason) {
    public NativeLlvmUnwindTargetSummary {
        Objects.requireNonNull(generatedCDecision, "generatedCDecision");
        Objects.requireNonNull(reason, "reason");
        if (moduleCount < 0
                || omittedModuleCount < 0
                || retainedModuleCount < 0
                || unmodeledObjectInputCount < 0
                || omittedModuleCount + retainedModuleCount != moduleCount) {
            throw new IllegalArgumentException("invalid LLVM unwind target counts");
        }
        if (finalOmissionExpected == effectiveRetention) {
            throw new IllegalArgumentException(
                    "final omission and effective retention must be complementary");
        }
    }
}
