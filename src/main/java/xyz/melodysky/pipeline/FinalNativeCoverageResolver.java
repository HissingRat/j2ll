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
import xyz.melodysky.toolchain.NativeImplementationUnavailableReasonClassifier;
import xyz.melodysky.toolchain.NativeLocalReferenceSafety;
import xyz.melodysky.toolchain.NativeMethodImplementation;
import xyz.melodysky.toolchain.localref.NativeLocalReferencePlanner;

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
    private final NativeLocalReferenceSafety localReferenceSafety =
            new NativeLocalReferenceSafety();
    private final NativeLocalReferencePlanner localReferencePlanner =
            new NativeLocalReferencePlanner();
    private final NativeImplementationUnavailableReasonClassifier
            unavailableReasonClassifier =
                    new NativeImplementationUnavailableReasonClassifier();

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
            String reasonCode = unavailableReasonCode(
                    result,
                    currentImplementationPlan);
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

    private String unavailableReasonCode(
            SsaMethodResult result,
            NativeImplementationPlan implementationPlan) {
        String plannedReason = implementationPlan
                .unavailableReasonCodeFor(
                        result.sourceMethod().methodKey())
                .orElse(null);
        if (plannedReason != null) {
            return plannedReason;
        }
        String structuralReason = result.irMethod()
                .flatMap(unavailableReasonClassifier::classify)
                .orElse(null);
        if (structuralReason != null) {
            return structuralReason;
        }
        if (result.irMethod()
                .filter(localReferenceSafety::hasUnboundedLocalReferenceRisk)
                .filter(method -> localReferencePlanner
                        .plan(method)
                        .plan()
                        .isEmpty())
                .isPresent()) {
            return FinalNativeCoverageDiagnostics
                    .UNBOUNDED_JNI_LOCAL_REFERENCE_LIFETIME
                    .value();
        }
        if (result.irMethod()
                .filter(exceptionFlowSupport::hasUnsupportedJvmFlow)
                .isPresent()) {
            return FinalNativeCoverageDiagnostics
                    .UNSUPPORTED_JVM_EXCEPTION_FLOW
                    .value();
        }
        return FinalNativeCoverageDiagnostics
                .NATIVE_IMPLEMENTATION_UNAVAILABLE
                .value();
    }

    private String unavailableReason(String reasonCode) {
        if (reasonCode.equals(FinalNativeCoverageDiagnostics
                .UNSUPPORTED_JVM_EXCEPTION_FLOW
                .value())) {
            return "JVM-throwable IR lacks complete pending-exception or handler-transfer evidence";
        }
        if (reasonCode.equals(FinalNativeCoverageDiagnostics
                .UNBOUNDED_JNI_LOCAL_REFERENCE_LIFETIME
                .value())) {
            return "a JNI-owned local reference can be created repeatedly inside a native control-flow or direct-call cycle";
        }
        if (reasonCode.equals(NativeImplementationUnavailableReasonClassifier
                .MULTIANEWARRAY_UNSUPPORTED)) {
            return "MULTIANEWARRAY has no complete JNI allocation runtime implementation";
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
