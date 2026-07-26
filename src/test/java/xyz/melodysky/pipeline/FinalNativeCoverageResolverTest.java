package xyz.melodysky.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodNode;
import xyz.melodysky.diagnostic.DiagnosticStage;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrExceptionEdge;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.ssa.SsaMethodResult;
import xyz.melodysky.jvm.AccessFlags;
import xyz.melodysky.packaging.MethodRewriteDecision;
import xyz.melodysky.packaging.MethodRewriteStrategy;
import xyz.melodysky.packaging.NativeRegistrationPlanner;
import xyz.melodysky.toolchain.NativeImplementationPath;
import xyz.melodysky.toolchain.NativeImplementationPlan;
import xyz.melodysky.toolchain.NativeImplementationPlanner;

class FinalNativeCoverageResolverTest implements Opcodes {
    private static final String OWNER = "pkg/Coverage";

    private final NativeImplementationPlanner implementationPlanner =
            new NativeImplementationPlanner();
    private final FinalNativeCoverageResolver resolver =
            new FinalNativeCoverageResolver(implementationPlanner);

    @Test
    void replansMissingOrdinaryMethodAsEmbeddedFallback() {
        ParsedMethod workingMethod = method("working", "()V");
        ParsedMethod fallbackMethod = method("backendGap", "()V");
        MethodRewriteDecision workingDecision = decision(workingMethod);
        MethodRewriteDecision fallbackDecision = decision(fallbackMethod);
        IrMethod workingIr = supportedVoidIr(workingMethod);
        IrMethod fallbackIr = unsupportedEmptyIr(fallbackMethod);
        List<MethodRewriteDecision> decisions =
                List.of(workingDecision, fallbackDecision);
        Map<String, IrMethod> finalIr = Map.of(
                workingMethod.methodKey(),
                workingIr,
                fallbackMethod.methodKey(),
                fallbackIr);
        NativeImplementationPlan currentPlan = plan(
                decisions,
                finalIr,
                Set.of());

        FinalNativeCoverageResult result = resolver.resolve(
                decisions,
                currentPlan,
                List.of(
                        SsaMethodResult.lowered(workingMethod, workingIr),
                        SsaMethodResult.lowered(fallbackMethod, fallbackIr)),
                finalIr,
                methodKeys(decisions),
                Set.of());

        assertEquals(2, result.implementedRewriteDecisions().size());
        assertEquals(2, result.finalImplementationPlan().implementations().size());
        var fallbackImplementation = result.finalImplementationPlan()
                .implementationFor(fallbackMethod.methodKey())
                .orElseThrow();
        assertEquals(
                NativeImplementationPath.TEMPLATE_JNI_PATH,
                fallbackImplementation.path());
        assertEquals(
                "NATIVE_EMBEDDED_CLASS_BLOB_FALLBACK",
                fallbackImplementation.reasonCode());
        SsaMethodResult finalFallback = result.finalSsaResults().get(1);
        assertEquals(LoweringStatus.HALF_LOWERED, finalFallback.status());
        assertEquals(
                "NATIVE_BACKEND_FALLBACK",
                finalFallback.reasonCode());
        assertTrue(finalFallback.irMethod().isPresent());
        assertEquals(1, result.diagnostics().size());
        assertEquals(
                DiagnosticStage.LLVM_MODEL,
                result.diagnostics().get(0).stage());
        assertEquals(
                "NATIVE_BACKEND_FALLBACK",
                result.diagnostics().get(0).code().value());
        assertEquals(
                LoweringStatus.HALF_LOWERED.wireName(),
                result.diagnostics().get(0).decision());
        assertTrue(result.diagnostics().get(0)
                .conservativeFallbackAvailable());
        assertFalse(currentPlan
                .implementationFor(fallbackMethod.methodKey())
                .isPresent());
    }

    @Test
    void usesCallerProvidedFallbackReasonClassifier() {
        ParsedMethod method = method("classifiedGap", "()V");
        MethodRewriteDecision decision = decision(method);
        IrMethod irMethod = unsupportedEmptyIr(method);
        List<MethodRewriteDecision> decisions = List.of(decision);
        Map<String, IrMethod> finalIr = Map.of(method.methodKey(), irMethod);

        FinalNativeCoverageResult result = resolver.resolve(
                decisions,
                plan(decisions, finalIr, Set.of()),
                List.of(SsaMethodResult.lowered(method, irMethod)),
                finalIr,
                methodKeys(decisions),
                Set.of(),
                ignored -> "CLASSIFIED_NATIVE_BACKEND_FALLBACK");

        assertEquals(
                "CLASSIFIED_NATIVE_BACKEND_FALLBACK",
                result.finalSsaResults().get(0).reasonCode());
        assertEquals(
                "CLASSIFIED_NATIVE_BACKEND_FALLBACK",
                result.diagnostics().get(0).code().value());
    }

    @Test
    void protectedJvmExceptionFlowUsesSpecificDefaultFallbackReason() {
        ParsedMethod method = method("protectedGap", "()V");
        MethodRewriteDecision decision = decision(method);
        IrMethod irMethod = unsupportedProtectedJvmFlowIr(method);
        List<MethodRewriteDecision> decisions = List.of(decision);
        Map<String, IrMethod> finalIr = Map.of(method.methodKey(), irMethod);

        FinalNativeCoverageResult result = resolver.resolve(
                decisions,
                plan(decisions, finalIr, Set.of()),
                List.of(SsaMethodResult.lowered(method, irMethod)),
                finalIr,
                methodKeys(decisions),
                Set.of());

        assertEquals(
                LoweringStatus.HALF_LOWERED,
                result.finalSsaResults().get(0).status());
        assertEquals(
                "UNSUPPORTED_PROTECTED_JVM_EXCEPTION_FLOW",
                result.finalSsaResults().get(0).reasonCode());
        assertEquals(
                "UNSUPPORTED_PROTECTED_JVM_EXCEPTION_FLOW",
                result.diagnostics().get(0).code().value());
    }

    @Test
    void skipsStillUnimplementedMethodAndFiltersRewriteDecisions() {
        ParsedMethod workingMethod = method("working", "()V");
        ParsedMethod unavailableMethod = method(
                "unavailable",
                "([[B)V");
        MethodRewriteDecision workingDecision = decision(workingMethod);
        MethodRewriteDecision unavailableDecision = decision(unavailableMethod);
        IrMethod workingIr = supportedVoidIr(workingMethod);
        List<MethodRewriteDecision> decisions =
                List.of(workingDecision, unavailableDecision);
        Map<String, IrMethod> finalIr =
                Map.of(workingMethod.methodKey(), workingIr);
        NativeImplementationPlan currentPlan = plan(
                decisions,
                finalIr,
                Set.of());

        FinalNativeCoverageResult result = resolver.resolve(
                decisions,
                currentPlan,
                List.of(
                        SsaMethodResult.lowered(workingMethod, workingIr),
                        SsaMethodResult.fallbackOnly(
                                unavailableMethod,
                                "ORIGINAL_FALLBACK_REASON",
                                "original fallback boundary")),
                finalIr,
                methodKeys(decisions),
                Set.of());

        assertEquals(
                List.of(workingMethod.methodKey()),
                result.implementedRewriteDecisions().stream()
                        .map(decision -> decision.method().methodKey())
                        .toList());
        assertEquals(
                List.of(workingMethod.methodKey()),
                result.finalImplementationPlan().implementations().stream()
                        .map(implementation -> implementation.methodKey())
                        .toList());
        SsaMethodResult unavailable = result.finalSsaResults().get(1);
        assertEquals(LoweringStatus.FRONTEND_SKIPPED, unavailable.status());
        assertEquals(
                "NATIVE_IMPLEMENTATION_UNAVAILABLE",
                unavailable.reasonCode());
        assertTrue(unavailable.irMethod().isEmpty());
        assertEquals(1, result.diagnostics().size());
        assertEquals(
                DiagnosticStage.LLVM_MODEL,
                result.diagnostics().get(0).stage());
        assertEquals(
                "NATIVE_IMPLEMENTATION_UNAVAILABLE",
                result.diagnostics().get(0).code().value());
        assertEquals(
                LoweringStatus.FRONTEND_SKIPPED.wireName(),
                result.diagnostics().get(0).decision());
    }

    private NativeImplementationPlan plan(
            List<MethodRewriteDecision> decisions,
            Map<String, IrMethod> irMethods,
            Set<String> fallbackMethodKeys) {
        return implementationPlanner.plan(
                new NativeRegistrationPlanner().plan(decisions),
                decisions,
                irMethods,
                fallbackMethodKeys,
                methodKeys(decisions),
                Set.of());
    }

    private Set<String> methodKeys(
            List<MethodRewriteDecision> decisions) {
        return decisions.stream()
                .map(decision -> decision.method().methodKey())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private MethodRewriteDecision decision(ParsedMethod method) {
        return new MethodRewriteDecision(
                method,
                MethodRewriteStrategy.NATIVE_ORIGINAL,
                method.owner(),
                Optional.empty(),
                null);
    }

    private ParsedMethod method(String name, String descriptor) {
        int access = ACC_PUBLIC | ACC_STATIC;
        return new ParsedMethod(
                OWNER,
                name,
                descriptor,
                new AccessFlags(access),
                List.of(),
                List.of(),
                List.of(),
                true,
                0,
                0,
                new MethodNode(
                        ASM9,
                        access,
                        name,
                        descriptor,
                        null,
                        null));
    }

    private IrMethod supportedVoidIr(ParsedMethod method) {
        return new IrMethod(
                method.owner(),
                method.name(),
                method.descriptor(),
                IrType.VOID,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(),
                        IrTerminator.returnVoid())));
    }

    private IrMethod unsupportedEmptyIr(ParsedMethod method) {
        return new IrMethod(
                method.owner(),
                method.name(),
                method.descriptor(),
                IrType.VOID,
                List.of(),
                List.of());
    }

    private IrMethod unsupportedProtectedJvmFlowIr(ParsedMethod method) {
        IrInstruction call = IrInstruction.call(
                Optional.empty(),
                IrOpcode.CALL_STATIC,
                List.of(),
                "pkg/Target#run!()V");
        return new IrMethod(
                method.owner(),
                method.name(),
                method.descriptor(),
                IrType.VOID,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(),
                        List.of(),
                        List.of(new IrExceptionEdge(
                                "catch",
                                "java/lang/RuntimeException")),
                        List.of(call),
                        IrTerminator.returnVoid())));
    }
}
