package xyz.melodysky.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticLocation;
import xyz.melodysky.diagnostic.DiagnosticStage;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.ir.ssa.SsaMethodResult;
import xyz.melodysky.packaging.MethodRewriteDecision;
import xyz.melodysky.toolchain.NativeExceptionFlowSupport;
import xyz.melodysky.toolchain.NativeImplementationPlan;
import xyz.melodysky.toolchain.NativeMethodImplementation;

/**
 * Closes the final rewrite-to-native implementation coverage gap.
 *
 * <p>A Code-bearing method is rewriteable only when the final native plan
 * contains its implementation. Missing backend coverage converts that entire
 * method to {@code skipped}; no partial IR or bytecode-copy implementation is
 * retained.</p>
 */
public final class FinalNativeCoverageResolver {
    private final NativeExceptionFlowSupport exceptionFlowSupport =
            new NativeExceptionFlowSupport();

    public FinalNativeCoverageResult resolve(
            List<MethodRewriteDecision> rewriteDecisions,
            NativeImplementationPlan currentImplementationPlan,
            List<SsaMethodResult> ssaResults) {
        Objects.requireNonNull(rewriteDecisions, "rewriteDecisions");
        Objects.requireNonNull(currentImplementationPlan, "currentImplementationPlan");
        Objects.requireNonNull(ssaResults, "ssaResults");

        Map<String, NativeMethodImplementation> implementations =
                currentImplementationPlan.implementations().stream()
                        .collect(Collectors.toMap(
                                NativeMethodImplementation::methodKey,
                                implementation -> implementation));
        Set<String> implementedMethodKeys = implementations.keySet();
        List<MethodRewriteDecision> implementedDecisions = rewriteDecisions.stream()
                .filter(decision -> implementedMethodKeys.contains(
                        decision.method().methodKey()))
                .toList();

        ArrayList<SsaMethodResult> finalResults =
                new ArrayList<>(ssaResults.size());
        ArrayList<Diagnostic> diagnostics = new ArrayList<>();
        for (SsaMethodResult result : ssaResults) {
            if (result.status() != LoweringStatus.NATIVE_LOWERED
                    || implementations.containsKey(
                            result.sourceMethod().methodKey())) {
                finalResults.add(result);
                continue;
            }
            String reasonCode = unavailableReasonCode(result);
            String reason = unavailableReason(reasonCode);
            finalResults.add(SsaMethodResult.skipped(
                    result.sourceMethod(),
                    DiagnosticStage.LLVM_MODEL,
                    reasonCode,
                    reason));
            diagnostics.add(unavailableDiagnostic(
                    result.sourceMethod(),
                    reasonCode,
                    reason));
        }
        diagnostics.sort(Diagnostic::compareTo);
        return new FinalNativeCoverageResult(
                implementedDecisions,
                currentImplementationPlan,
                finalResults,
                diagnostics);
    }

    private String unavailableReasonCode(SsaMethodResult result) {
        if (result.irMethod()
                .filter(exceptionFlowSupport::hasUnsupportedProtectedJvmFlow)
                .isPresent()) {
            return FinalNativeCoverageDiagnostics
                    .UNSUPPORTED_PROTECTED_JVM_EXCEPTION_FLOW
                    .value();
        }
        return FinalNativeCoverageDiagnostics
                .NATIVE_IMPLEMENTATION_UNAVAILABLE
                .value();
    }

    private String unavailableReason(String reasonCode) {
        if (reasonCode.equals(FinalNativeCoverageDiagnostics
                .UNSUPPORTED_PROTECTED_JVM_EXCEPTION_FLOW
                .value())) {
            return "JNI/runtime-helper exceptions protected by an in-method Java catch "
                    + "are not yet materialized as native exception control flow";
        }
        return "no safe final native implementation is available";
    }

    private Diagnostic unavailableDiagnostic(
            ParsedMethod method,
            String reasonCode,
            String reason) {
        return Diagnostic.warning(
                        DiagnosticStage.LLVM_MODEL,
                        xyz.melodysky.diagnostic.DiagnosticCode.of(reasonCode),
                        reason + "; retaining the original Java method")
                .at(DiagnosticLocation.methodLocation(
                        method.owner(),
                        method.name(),
                        method.descriptor()))
                .withDecision(LoweringStatus.SKIPPED.wireName());
    }
}
