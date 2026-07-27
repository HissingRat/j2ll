package xyz.melodysky.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import xyz.melodysky.toolchain.NativeImplementationPlan;
import xyz.melodysky.toolchain.NativeImplementationPlanner;

class FinalNativeCoverageResolverTest implements Opcodes {
    private static final String OWNER = "pkg/Coverage";

    private final NativeImplementationPlanner implementationPlanner =
            new NativeImplementationPlanner();
    private final FinalNativeCoverageResolver resolver =
            new FinalNativeCoverageResolver();

    @Test
    void keepsMethodsThatHaveFinalNativeImplementations() {
        ParsedMethod method = method("working", "()V");
        MethodRewriteDecision decision = decision(method);
        IrMethod irMethod = supportedVoidIr(method);
        NativeImplementationPlan plan = plan(List.of(decision), Map.of(method.methodKey(), irMethod));

        FinalNativeCoverageResult result = resolver.resolve(
                List.of(decision),
                plan,
                List.of(SsaMethodResult.nativeLowered(method, irMethod)));

        assertEquals(List.of(decision), result.implementedRewriteDecisions());
        assertEquals(plan, result.finalImplementationPlan());
        assertEquals(LoweringStatus.NATIVE_LOWERED, result.finalSsaResults().get(0).status());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void convertsMissingBackendCoverageToSkippedAndFiltersRewriteDecision() {
        ParsedMethod workingMethod = method("working", "()V");
        ParsedMethod missingMethod = method("backendGap", "()V");
        MethodRewriteDecision workingDecision = decision(workingMethod);
        MethodRewriteDecision missingDecision = decision(missingMethod);
        IrMethod workingIr = supportedVoidIr(workingMethod);
        IrMethod unsupportedIr = unsupportedEmptyIr(missingMethod);
        List<MethodRewriteDecision> decisions = List.of(workingDecision, missingDecision);
        NativeImplementationPlan plan = plan(
                decisions,
                Map.of(
                        workingMethod.methodKey(), workingIr,
                        missingMethod.methodKey(), unsupportedIr));

        FinalNativeCoverageResult result = resolver.resolve(
                decisions,
                plan,
                List.of(
                        SsaMethodResult.nativeLowered(workingMethod, workingIr),
                        SsaMethodResult.nativeLowered(missingMethod, unsupportedIr)));

        assertEquals(List.of(workingDecision), result.implementedRewriteDecisions());
        assertEquals(1, result.finalImplementationPlan().implementations().size());
        SsaMethodResult skipped = result.finalSsaResults().get(1);
        assertEquals(LoweringStatus.SKIPPED, skipped.status());
        assertEquals("NATIVE_IMPLEMENTATION_UNAVAILABLE", skipped.reasonCode());
        assertTrue(skipped.irMethod().isEmpty());
        assertEquals(1, result.diagnostics().size());
        assertEquals(DiagnosticStage.LLVM_MODEL, result.diagnostics().get(0).stage());
        assertEquals("skipped", result.diagnostics().get(0).decision());
    }

    @Test
    void usesSpecificReasonForProtectedJvmExceptionFlow() {
        ParsedMethod method = method("protectedGap", "()V");
        MethodRewriteDecision decision = decision(method);
        IrMethod irMethod = unsupportedProtectedJvmFlowIr(method);
        NativeImplementationPlan plan = plan(List.of(decision), Map.of(method.methodKey(), irMethod));

        FinalNativeCoverageResult result = resolver.resolve(
                List.of(decision),
                plan,
                List.of(SsaMethodResult.nativeLowered(method, irMethod)));

        SsaMethodResult skipped = result.finalSsaResults().get(0);
        assertEquals(LoweringStatus.SKIPPED, skipped.status());
        assertEquals("UNSUPPORTED_PROTECTED_JVM_EXCEPTION_FLOW", skipped.reasonCode());
        assertEquals(
                "UNSUPPORTED_PROTECTED_JVM_EXCEPTION_FLOW",
                result.diagnostics().get(0).code().value());
    }

    @Test
    void preservesAlreadySkippedFrontendResultWithoutDuplicateDiagnostic() {
        ParsedMethod method = method("unsupported", "()V");
        SsaMethodResult skipped = SsaMethodResult.skipped(
                method,
                "UNSUPPORTED_TEST_SHAPE",
                "unsupported test shape");

        FinalNativeCoverageResult result = resolver.resolve(
                List.of(decision(method)),
                new NativeImplementationPlan(List.of()),
                List.of(skipped));

        assertEquals(List.of(), result.implementedRewriteDecisions());
        assertEquals(List.of(skipped), result.finalSsaResults());
        assertTrue(result.diagnostics().isEmpty());
    }

    private NativeImplementationPlan plan(
            List<MethodRewriteDecision> decisions,
            Map<String, IrMethod> irMethods) {
        return implementationPlanner.plan(
                new NativeRegistrationPlanner().plan(decisions),
                decisions,
                irMethods,
                decisions.stream()
                        .map(decision -> decision.method().methodKey())
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()));
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
                new MethodNode(ASM9, access, name, descriptor, null, null));
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
