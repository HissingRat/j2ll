package xyz.melodysky.backend.llvm.model;

import java.util.List;
import java.util.Objects;

/** Immutable module-level proof consumed by target-specific LLVM emission. */
public record LlvmNativeUnwindProof(
        boolean omissionSafe,
        String reasonCode,
        List<LlvmNativeUnwindFinding> findings) {
    public static final String PROVEN_ABSENT =
            "LLVM_NATIVE_UNWIND_PROVEN_ABSENT";
    public static final String REQUIRED =
            "LLVM_NATIVE_UNWIND_REQUIRED";
    public static final String PROOF_INCOMPLETE =
            "LLVM_NATIVE_UNWIND_PROOF_INCOMPLETE";

    public LlvmNativeUnwindProof {
        Objects.requireNonNull(reasonCode, "reasonCode");
        findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
        if (omissionSafe != findings.isEmpty()) {
            throw new IllegalArgumentException(
                    "unwind omission is safe exactly when there are no findings");
        }
        if (omissionSafe && !reasonCode.equals(PROVEN_ABSENT)) {
            throw new IllegalArgumentException("safe unwind proof has the wrong reason code");
        }
        if (!omissionSafe) {
            boolean required = findings.stream().anyMatch(finding ->
                    finding.semantics() == LlvmNativeUnwindSemantics.REQUIRED);
            String expected = required ? REQUIRED : PROOF_INCOMPLETE;
            if (!reasonCode.equals(expected)) {
                throw new IllegalArgumentException(
                        "unsafe unwind proof has the wrong reason code");
            }
        }
    }
}
