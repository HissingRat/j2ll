package xyz.melodysky.pipeline;

import java.util.List;
import java.util.Objects;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.ir.ssa.SsaMethodResult;
import xyz.melodysky.packaging.MethodRewriteDecision;
import xyz.melodysky.toolchain.NativeImplementationPlan;

public record FinalNativeCoverageResult(
        List<MethodRewriteDecision> implementedRewriteDecisions,
        NativeImplementationPlan finalImplementationPlan,
        List<SsaMethodResult> finalSsaResults,
        List<Diagnostic> diagnostics) {
    public FinalNativeCoverageResult {
        implementedRewriteDecisions = List.copyOf(
                Objects.requireNonNull(
                        implementedRewriteDecisions,
                        "implementedRewriteDecisions"));
        Objects.requireNonNull(finalImplementationPlan, "finalImplementationPlan");
        finalSsaResults = List.copyOf(
                Objects.requireNonNull(finalSsaResults, "finalSsaResults"));
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }
}
