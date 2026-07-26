package xyz.melodysky.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticCode;
import xyz.melodysky.diagnostic.DiagnosticLocation;
import xyz.melodysky.diagnostic.DiagnosticStage;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.ssa.SsaMethodResult;
import xyz.melodysky.packaging.MethodRewriteDecision;
import xyz.melodysky.packaging.MethodRewriteStrategy;
import xyz.melodysky.packaging.NativeRegistrationPlanner;
import xyz.melodysky.toolchain.NativeImplementationPlan;
import xyz.melodysky.toolchain.NativeImplementationPlanner;
import xyz.melodysky.toolchain.NativeExceptionFlowSupport;
import xyz.melodysky.toolchain.NativeMethodImplementation;

/**
 * Closes the final rewrite-to-native implementation coverage gap.
 *
 * <p>Only ordinary methods can be promoted to the encoded
 * nativeEmbeddedClassBlob fallback. Methods that still have no implementation
 * are conservatively restored to frontend-skipped status.
 */
public final class FinalNativeCoverageResolver {
    private static final String EMBEDDED_FALLBACK_REASON =
            "NATIVE_EMBEDDED_CLASS_BLOB_FALLBACK";

    private final NativeImplementationPlanner implementationPlanner;
    private final NativeExceptionFlowSupport exceptionFlowSupport =
            new NativeExceptionFlowSupport();
    private final NativeRegistrationPlanner registrationPlanner =
            new NativeRegistrationPlanner();

    public FinalNativeCoverageResolver() {
        this(new NativeImplementationPlanner());
    }

    public FinalNativeCoverageResolver(
            NativeImplementationPlanner implementationPlanner) {
        this.implementationPlanner = Objects.requireNonNull(
                implementationPlanner,
                "implementationPlanner");
    }

    public FinalNativeCoverageResult resolve(
            List<MethodRewriteDecision> rewriteDecisions,
            NativeImplementationPlan currentImplementationPlan,
            List<SsaMethodResult> ssaResults,
            Map<String, IrMethod> finalIrMethods,
            Set<String> availableProgramMethodKeys,
            Set<String> compilerInternalMethodKeys) {
        return resolve(
                rewriteDecisions,
                currentImplementationPlan,
                ssaResults,
                finalIrMethods,
                availableProgramMethodKeys,
                compilerInternalMethodKeys,
                this::defaultFallbackReason);
    }

    public FinalNativeCoverageResult resolve(
            List<MethodRewriteDecision> rewriteDecisions,
            NativeImplementationPlan currentImplementationPlan,
            List<SsaMethodResult> ssaResults,
            Map<String, IrMethod> finalIrMethods,
            Set<String> availableProgramMethodKeys,
            Set<String> compilerInternalMethodKeys,
            Function<SsaMethodResult, String> fallbackReasonClassifier) {
        Objects.requireNonNull(rewriteDecisions, "rewriteDecisions");
        Objects.requireNonNull(currentImplementationPlan, "currentImplementationPlan");
        Objects.requireNonNull(ssaResults, "ssaResults");
        Objects.requireNonNull(finalIrMethods, "finalIrMethods");
        Objects.requireNonNull(availableProgramMethodKeys, "availableProgramMethodKeys");
        Objects.requireNonNull(compilerInternalMethodKeys, "compilerInternalMethodKeys");
        Objects.requireNonNull(fallbackReasonClassifier, "fallbackReasonClassifier");

        Set<String> initiallyImplemented = implementedMethodKeys(
                currentImplementationPlan);
        LinkedHashSet<String> fallbackMethodKeys = ssaResults.stream()
                .filter(result -> result.status() == LoweringStatus.HALF_LOWERED)
                .map(result -> result.sourceMethod().methodKey())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        LinkedHashSet<String> retryMethodKeys = rewriteDecisions.stream()
                .filter(decision ->
                        decision.strategy() == MethodRewriteStrategy.NATIVE_ORIGINAL)
                .map(decision -> decision.method().methodKey())
                .filter(methodKey -> !initiallyImplemented.contains(methodKey))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        fallbackMethodKeys.addAll(retryMethodKeys);

        NativeImplementationPlan finalPlan = retryMethodKeys.isEmpty()
                ? currentImplementationPlan
                : implementationPlanner.plan(
                        registrationPlanner.plan(rewriteDecisions),
                        rewriteDecisions,
                        finalIrMethods,
                        fallbackMethodKeys,
                        availableProgramMethodKeys,
                        compilerInternalMethodKeys);
        Map<String, NativeMethodImplementation> finalImplementations =
                finalPlan.implementations().stream().collect(Collectors.toMap(
                        NativeMethodImplementation::methodKey,
                        implementation -> implementation));
        Set<String> finallyImplemented = finalImplementations.keySet();

        List<MethodRewriteDecision> implementedDecisions = rewriteDecisions.stream()
                .filter(decision -> finallyImplemented.contains(
                        decision.method().methodKey()))
                .toList();
        ArrayList<SsaMethodResult> finalSsaResults =
                new ArrayList<>(ssaResults.size());
        ArrayList<Diagnostic> diagnostics = new ArrayList<>();
        for (SsaMethodResult result : ssaResults) {
            if (result.status() != LoweringStatus.LOWERED
                    && result.status() != LoweringStatus.HALF_LOWERED) {
                finalSsaResults.add(result);
                continue;
            }
            String methodKey = result.sourceMethod().methodKey();
            NativeMethodImplementation implementation =
                    finalImplementations.get(methodKey);
            if (implementation == null) {
                finalSsaResults.add(unavailable(result));
                diagnostics.add(unavailableDiagnostic(result.sourceMethod()));
                continue;
            }
            if (result.status() == LoweringStatus.LOWERED
                    && implementation.reasonCode().equals(
                            EMBEDDED_FALLBACK_REASON)) {
                String reasonCode = classifiedReason(
                        fallbackReasonClassifier,
                        result);
                finalSsaResults.add(new SsaMethodResult(
                        result.sourceMethod(),
                        result.irMethod(),
                        LoweringStatus.HALF_LOWERED,
                        reasonCode,
                        fallbackReason(reasonCode)));
                diagnostics.add(fallbackDiagnostic(
                        result.sourceMethod(),
                        reasonCode));
                continue;
            }
            finalSsaResults.add(result);
        }
        diagnostics.sort(Diagnostic::compareTo);
        return new FinalNativeCoverageResult(
                implementedDecisions,
                finalPlan,
                finalSsaResults,
                diagnostics);
    }

    private Set<String> implementedMethodKeys(
            NativeImplementationPlan implementationPlan) {
        return implementationPlan.implementations().stream()
                .map(NativeMethodImplementation::methodKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    private String classifiedReason(
            Function<SsaMethodResult, String> fallbackReasonClassifier,
            SsaMethodResult result) {
        String reasonCode = Objects.requireNonNull(
                fallbackReasonClassifier.apply(result),
                "fallback reason classifier result");
        if (reasonCode.isBlank()) {
            throw new IllegalArgumentException(
                    "fallback reason classifier must return a non-blank reason");
        }
        return reasonCode;
    }

    private String defaultFallbackReason(SsaMethodResult result) {
        if (result.irMethod()
                .filter(exceptionFlowSupport::hasUnsupportedProtectedJvmFlow)
                .isPresent()) {
            return FinalNativeCoverageDiagnostics
                    .UNSUPPORTED_PROTECTED_JVM_EXCEPTION_FLOW
                    .value();
        }
        return FinalNativeCoverageDiagnostics.NATIVE_BACKEND_FALLBACK.value();
    }

    private SsaMethodResult unavailable(SsaMethodResult result) {
        return SsaMethodResult.frontendSkipped(
                result.sourceMethod(),
                FinalNativeCoverageDiagnostics
                        .NATIVE_IMPLEMENTATION_UNAVAILABLE
                        .value(),
                "no safe final native implementation or embedded bytecode "
                        + "fallback is available");
    }

    private String fallbackReason(String reasonCode) {
        if (reasonCode.equals(FinalNativeCoverageDiagnostics
                .UNSUPPORTED_PROTECTED_JVM_EXCEPTION_FLOW
                .value())) {
            return "JNI/runtime-helper exceptions protected by an in-method Java catch "
                    + "are not yet materialized as native exception control flow; "
                    + "nativeEmbeddedClassBlob preserves the original bytecode semantics";
        }
        return "native backend fallback preserved the original bytecode through "
                + "nativeEmbeddedClassBlob";
    }

    private Diagnostic fallbackDiagnostic(
            ParsedMethod method,
            String reasonCode) {
        return Diagnostic.warning(
                        DiagnosticStage.LLVM_MODEL,
                        DiagnosticCode.of(reasonCode),
                        "native backend implementation is unavailable; using "
                                + "nativeEmbeddedClassBlob fallback")
                .at(DiagnosticLocation.methodLocation(
                        method.owner(),
                        method.name(),
                        method.descriptor()))
                .withDecision(LoweringStatus.HALF_LOWERED.wireName())
                .withConservativeFallbackAvailable(true);
    }

    private Diagnostic unavailableDiagnostic(ParsedMethod method) {
        return Diagnostic.warning(
                        DiagnosticStage.LLVM_MODEL,
                        FinalNativeCoverageDiagnostics
                                .NATIVE_IMPLEMENTATION_UNAVAILABLE,
                        "no safe final native implementation or embedded "
                                + "bytecode fallback is available; retaining "
                                + "the original Java method")
                .at(DiagnosticLocation.methodLocation(
                        method.owner(),
                        method.name(),
                        method.descriptor()))
                .withDecision(LoweringStatus.FRONTEND_SKIPPED.wireName());
    }
}
