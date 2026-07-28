package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import xyz.melodysky.backend.llvm.model.LlvmBasicBlock;
import xyz.melodysky.backend.llvm.model.LlvmDeclaration;
import xyz.melodysky.backend.llvm.model.LlvmFunction;
import xyz.melodysky.backend.llvm.model.LlvmGlobal;
import xyz.melodysky.backend.llvm.model.LlvmInstruction;
import xyz.melodysky.backend.llvm.model.LlvmLinkage;
import xyz.melodysky.backend.llvm.model.LlvmModule;
import xyz.melodysky.backend.llvm.model.LlvmTerminator;
import xyz.melodysky.backend.llvm.model.LlvmType;
import xyz.melodysky.backend.llvm.model.LlvmVisibility;
import xyz.melodysky.backend.llvm.protection.LlvmBlockLayoutPerturbationResult;
import xyz.melodysky.backend.llvm.protection.LlvmCallIndirectionResult;
import xyz.melodysky.backend.llvm.protection.LlvmGlobalLayoutResult;
import xyz.melodysky.backend.llvm.protection.LlvmIrCallIndirectionResult;
import xyz.melodysky.backend.llvm.protection.LlvmOpaquePredicateResult;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrType;

final class RuntimeHelperReachabilityPlanTest {
    @Test
    void usesFinalModuleModelAndDoesNotTreatDeclarationsOrLlvmTextAsRoots() {
        LlvmModule module = module(
                List.of(new LlvmDeclaration(
                        "j2ll_rt_array_length_i32",
                        "i32",
                        List.of("ptr", "ptr"),
                        "arrayLengthI32")),
                List.of(),
                List.of(LlvmInstruction.raw(
                        Optional.of("%result"),
                        "call i32 @j2ll_rt_math_abs_i32(i32 %value)")),
                new LlvmTerminator(
                        LlvmType.I32,
                        Optional.of("%result")));

        RuntimeHelperReachabilityPlan plan = RuntimeHelperReachabilityPlan.from(
                compilation(
                        module,
                        "call i32 @j2ll_rt_array_length_i32(ptr null, ptr null)"));

        assertFalse(plan.isConservative());
        assertEquals(
                Set.of("j2ll_rt_math_abs_i32"),
                plan.rootSymbols());
        assertEquals(
                Set.of(HostJniRuntimeSourceFamily.MATH),
                plan.families());
    }

    @Test
    void followsLocalizedClassForNameSourceClosure() {
        String helper = "j2ll_h_0123456789abcdef";
        LlvmModule module = module(
                List.of(new LlvmDeclaration(
                        helper,
                        "ptr",
                        List.of("ptr", "i32"),
                        "localizedClassForName")),
                List.of(),
                List.of(LlvmInstruction.raw(
                        Optional.of("%result"),
                        "call ptr @" + helper
                                + "(ptr %j2ll_env, i32 1)")),
                new LlvmTerminator(
                        LlvmType.PTR,
                        Optional.of("%result")));

        RuntimeHelperReachabilityPlan plan =
                RuntimeHelperReachabilityPlan.from(
                        compilation(module, ""));

        assertFalse(plan.isConservative());
        assertEquals(
                Set.of(
                        HostJniRuntimeSourceFamily.ALLOCATION,
                        HostJniRuntimeSourceFamily.REFLECTION),
                plan.families());
    }

    @Test
    void unsafeReferenceAccessPullsVarHandleSupportIntoClosure() {
        LlvmModule module = module(
                List.of(),
                List.of(),
                List.of(LlvmInstruction.raw(
                        Optional.of("%result"),
                        "call ptr @j2ll_rt_unsafe_get("
                                + "ptr %j2ll_env, ptr %handle, ptr %target)")),
                new LlvmTerminator(
                        LlvmType.PTR,
                        Optional.of("%result")));

        RuntimeHelperReachabilityPlan plan =
                RuntimeHelperReachabilityPlan.from(
                        compilation(module, ""));

        assertEquals(
                Set.of(
                        HostJniRuntimeSourceFamily.REFLECTION,
                        HostJniRuntimeSourceFamily.VAR_HANDLE),
                plan.families());
    }

    @Test
    void scansGlobalFunctionPointerRootsAndStructuredThrowTerminators() {
        LlvmModule module = module(
                List.of(),
                List.of(new LlvmGlobal(
                        "runtime_table",
                        "internal constant [1 x ptr] "
                                + "[ptr @j2ll_rt_thread_sleep]")),
                List.of(),
                LlvmTerminator.throwValue("%throwable"));

        RuntimeHelperReachabilityPlan plan =
                RuntimeHelperReachabilityPlan.from(
                        compilation(module, ""));

        assertEquals(
                Set.of(
                        "j2ll_rt_thread_sleep",
                        "j2ll_rt_throw"),
                plan.rootSymbols());
        assertEquals(
                Set.of(
                        HostJniRuntimeSourceFamily.THREAD,
                        HostJniRuntimeSourceFamily.EXCEPTION),
                plan.families());
    }

    @Test
    void unknownRuntimeOrLocalizedSymbolFailsClosedToAllFamilies() {
        LlvmModule stableUnknown = module(
                List.of(),
                List.of(),
                List.of(LlvmInstruction.raw(
                        Optional.empty(),
                        "call void @j2ll_rt_future_helper()")),
                new LlvmTerminator(
                        LlvmType.VOID,
                        Optional.empty()));
        RuntimeHelperReachabilityPlan stablePlan =
                RuntimeHelperReachabilityPlan.from(
                        compilation(stableUnknown, ""));

        assertTrue(stablePlan.isConservative());
        assertEquals(
                Set.of(HostJniRuntimeSourceFamily.values()),
                stablePlan.families());

        LlvmModule localizedUnknown = module(
                List.of(),
                List.of(),
                List.of(LlvmInstruction.raw(
                        Optional.empty(),
                        "call void @j2ll_h_ffffffffffffffff()")),
                new LlvmTerminator(
                        LlvmType.VOID,
                        Optional.empty()));
        RuntimeHelperReachabilityPlan localizedPlan =
                RuntimeHelperReachabilityPlan.from(
                        compilation(localizedUnknown, ""));

        assertTrue(localizedPlan.isConservative());
        assertEquals(
                Set.of(HostJniRuntimeSourceFamily.values()),
                localizedPlan.families());

        LlvmModule malformedReference = module(
                List.of(),
                List.of(),
                List.of(LlvmInstruction.raw(
                        Optional.empty(),
                        "call void @\"unterminated()")),
                new LlvmTerminator(
                        LlvmType.VOID,
                        Optional.empty()));
        RuntimeHelperReachabilityPlan malformedPlan =
                RuntimeHelperReachabilityPlan.from(
                        compilation(malformedReference, ""));

        assertTrue(malformedPlan.isConservative());

        LlvmModule escapedQuotedReference = module(
                List.of(),
                List.of(),
                List.of(LlvmInstruction.raw(
                        Optional.empty(),
                        "call void @\"\\6A2ll_rt_future_helper\"()")),
                new LlvmTerminator(
                        LlvmType.VOID,
                        Optional.empty()));
        RuntimeHelperReachabilityPlan escapedPlan =
                RuntimeHelperReachabilityPlan.from(
                        compilation(escapedQuotedReference, ""));

        assertTrue(escapedPlan.isConservative());

        LlvmModule knownPrefixButUnknownSymbol = module(
                List.of(),
                List.of(),
                List.of(LlvmInstruction.raw(
                        Optional.empty(),
                        "call void @j2ll_rt_math_future()")),
                new LlvmTerminator(
                        LlvmType.VOID,
                        Optional.empty()));
        RuntimeHelperReachabilityPlan prefixPlan =
                RuntimeHelperReachabilityPlan.from(
                        compilation(knownPrefixButUnknownSymbol, ""));

        assertTrue(prefixPlan.isConservative());
    }

    @Test
    void buildLocalBusinessStringRequiresItsExactHashAndDeclarationEvidence() {
        String helper = "j2ll_rt_string_constant_"
                + "0123456789abcdef0123456789abcdef";
        LlvmModule evidenced = module(
                List.of(new LlvmDeclaration(
                        helper,
                        "ptr",
                        List.of("ptr"),
                        "businessStringConstantLocal")),
                List.of(),
                List.of(LlvmInstruction.raw(
                        Optional.of("%result"),
                        "call ptr @" + helper + "(ptr %j2ll_env)")),
                new LlvmTerminator(
                        LlvmType.PTR,
                        Optional.of("%result")));

        RuntimeHelperReachabilityPlan evidencedPlan =
                RuntimeHelperReachabilityPlan.from(
                        compilation(evidenced, ""));

        assertFalse(evidencedPlan.isConservative());
        assertTrue(evidencedPlan.families().isEmpty());

        LlvmModule missingEvidence = module(
                List.of(),
                List.of(),
                List.of(LlvmInstruction.raw(
                        Optional.of("%result"),
                        "call ptr @" + helper + "(ptr %j2ll_env)")),
                new LlvmTerminator(
                        LlvmType.PTR,
                        Optional.of("%result")));

        assertTrue(RuntimeHelperReachabilityPlan.from(
                        compilation(missingEvidence, ""))
                .isConservative());
    }

    @Test
    void physicalBindingDrivenEmissionClosesCrossFamilyDependencies() {
        String allocationHelper = "j2ll_h_0123456789abcdef";
        RuntimeHelperReachabilityPlan allocationPlan =
                RuntimeHelperReachabilityPlan.from(compilation(
                        module(
                                List.of(new LlvmDeclaration(
                                        allocationHelper,
                                        "ptr",
                                        List.of("ptr"),
                                        "localizedTypeCheck")),
                                List.of(),
                                List.of(LlvmInstruction.raw(
                                        Optional.of("%result"),
                                        "call ptr @"
                                                + allocationHelper
                                                + "(ptr %j2ll_env)")),
                                new LlvmTerminator(
                                        LlvmType.PTR,
                                        Optional.of("%result"))),
                        ""));
        HostJniCSourceGenerator.Binding staleClassForName =
                binding(
                        List.of("class:java/lang/String"),
                        Optional.empty());

        assertEquals(
                Set.of(
                        HostJniRuntimeSourceFamily.ALLOCATION,
                        HostJniRuntimeSourceFamily.REFLECTION),
                new HostJniReachableRuntimeSourceEmitter()
                        .emissionFamilies(
                                List.of(staleClassForName),
                                allocationPlan));

        RuntimeHelperReachabilityPlan reflectionPlan =
                RuntimeHelperReachabilityPlan.from(compilation(
                        module(
                                List.of(),
                                List.of(),
                                List.of(LlvmInstruction.raw(
                                        Optional.of("%result"),
                                        "call ptr @j2ll_rt_reflect_invoke("
                                                + "ptr %j2ll_env, ptr null, "
                                                + "ptr null, ptr null)")),
                                new LlvmTerminator(
                                        LlvmType.PTR,
                                        Optional.of("%result"))),
                        ""));
        IrMethod staleUnsafeGet = new IrMethod(
                "pkg/Fixture",
                "stale",
                "()V",
                IrType.VOID,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(xyz.melodysky.ir.model.IrInstruction.operation(
                                Optional.empty(),
                                IrOpcode.CALL_RUNTIME_HELPER,
                                List.of(),
                                "j2ll_rt_unsafe_get")),
                        IrTerminator.returnVoid())));

        assertEquals(
                Set.of(
                        HostJniRuntimeSourceFamily.REFLECTION,
                        HostJniRuntimeSourceFamily.VAR_HANDLE),
                new HostJniReachableRuntimeSourceEmitter()
                        .emissionFamilies(
                                List.of(binding(
                                        List.of(),
                                        Optional.of(staleUnsafeGet))),
                                reflectionPlan));
    }

    private LlvmModule module(
            List<LlvmDeclaration> declarations,
            List<LlvmGlobal> globals,
            List<LlvmInstruction> instructions,
            LlvmTerminator terminator) {
        return new LlvmModule(
                "pkg/Fixture",
                declarations,
                globals,
                List.of(new LlvmFunction(
                        "fixture",
                        LlvmLinkage.EXTERNAL,
                        LlvmVisibility.HIDDEN,
                        terminator.returnType(),
                        List.of(),
                        List.of(new LlvmBasicBlock(
                                "entry",
                                instructions,
                                terminator)))));
    }

    private NativeLlvmCompilation compilation(
            LlvmModule module,
            String llvmText) {
        return new NativeLlvmCompilation(
                "fixture-input",
                List.of(new NativeLlvmModuleCompilation(
                        module.identifier(),
                        List.of(),
                        List.of(),
                        new LlvmBlockLayoutPerturbationResult(
                                module,
                                List.of(),
                                List.of()),
                        new LlvmOpaquePredicateResult(
                                module,
                                List.of(),
                                List.of()),
                        new LlvmIrCallIndirectionResult(
                                module,
                                List.of(),
                                List.of(),
                                List.of()),
                        new LlvmCallIndirectionResult(
                                module,
                                List.of(),
                                List.of(),
                                "TEST"),
                        new LlvmGlobalLayoutResult(
                                module,
                                List.of(),
                                List.of()),
                        llvmText)));
    }

    private HostJniCSourceGenerator.Binding binding(
            List<String> runtimeMetadataKeys,
            Optional<IrMethod> templateIrMethod) {
        return new HostJniCSourceGenerator.Binding(
                null,
                null,
                NativeImplementationPath.LLVM_NATIVE_PATH,
                Optional.empty(),
                false,
                false,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                runtimeMetadataKeys,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                templateIrMethod,
                "TEST",
                null);
    }
}
