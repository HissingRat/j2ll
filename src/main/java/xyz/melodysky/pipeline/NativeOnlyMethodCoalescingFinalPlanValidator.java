package xyz.melodysky.pipeline;

import java.util.List;
import xyz.melodysky.analysis.method.NativeOnlyMethodCoalescingPlan;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticCode;
import xyz.melodysky.diagnostic.DiagnosticStage;
import xyz.melodysky.toolchain.NativeImplementationPlan;
import xyz.melodysky.toolchain.NativeLlvmCompilation;
import xyz.melodysky.toolchain.NativeOnlyMethodCoalescingEmissionVerifier;

/** Fails the build if a planned coalesced body survives LLVM emission. */
public final class NativeOnlyMethodCoalescingFinalPlanValidator {
    private static final DiagnosticCode MISMATCH = DiagnosticCode.of(
            "NATIVE_ONLY_COALESCING_FINAL_EMISSION_MISMATCH");

    public List<Diagnostic> validate(
            NativeOnlyMethodCoalescingPlan plan,
            NativeImplementationPlan implementationPlan,
            NativeLlvmCompilation compilation) {
        List<String> residuals =
                new NativeOnlyMethodCoalescingEmissionVerifier().residuals(
                        plan,
                        implementationPlan,
                        compilation);
        if (residuals.isEmpty()) {
            return List.of();
        }
        return List.of(Diagnostic.error(
                        DiagnosticStage.PROTECTION,
                        MISMATCH,
                        "coalesced native-only methods retained a standalone LLVM surface: "
                                + residuals)
                .withDecision("failed"));
    }
}
