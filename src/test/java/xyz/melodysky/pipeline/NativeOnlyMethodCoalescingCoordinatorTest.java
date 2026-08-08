package xyz.melodysky.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodNode;
import xyz.melodysky.analysis.method.NativeMethodId;
import xyz.melodysky.analysis.method.NativeMethodInternalizationDecision;
import xyz.melodysky.analysis.method.NativeMethodInternalizationPlan;
import xyz.melodysky.analysis.method.NativeMethodInternalizationReason;
import xyz.melodysky.analysis.method.NativeMethodInternalizationStatus;
import xyz.melodysky.analysis.method.NativeOnlyMethodCoalescingReason;
import xyz.melodysky.analysis.world.WholeProgramAnalysisScope;
import xyz.melodysky.backend.llvm.LlvmModuleLowerer;
import xyz.melodysky.backend.llvm.LlvmNameMangler;
import xyz.melodysky.backend.llvm.model.LlvmTextEmitter;
import xyz.melodysky.backend.llvm.protection.LlvmProtectionConfig;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;
import xyz.melodysky.ir.pass.protection.MethodInliningReason;
import xyz.melodysky.jvm.AccessFlags;
import xyz.melodysky.packaging.MethodRewriteDecision;
import xyz.melodysky.packaging.MethodRewriteStrategy;
import xyz.melodysky.packaging.NativeRegistrationEntry;
import xyz.melodysky.protection.audit.ProtectionApplicability;
import xyz.melodysky.toolchain.NativeImplementationPath;
import xyz.melodysky.toolchain.NativeImplementationPlan;
import xyz.melodysky.toolchain.NativeLlvmCompiler;
import xyz.melodysky.toolchain.NativeMethodImplementation;
import xyz.melodysky.toolchain.NativeOnlyMethodCoalescingEmissionVerifier;
import xyz.melodysky.toolchain.initializer.InitializerImplementationPlan;
import xyz.melodysky.toolchain.localref.NativeLocalReferencePlan;

class NativeOnlyMethodCoalescingCoordinatorTest {
    @Test
    void mergesUniquePureNativeOnlyCalleeAndOmitsItsLlvmFunction(
            @TempDir Path workspace) throws Exception {
        IrMethod callee = increment("helper");
        IrMethod caller = caller("entry", callee, 1);
        NativeImplementationPlan implementations = implementationPlan(
                caller,
                callee,
                Map.of());

        NativeOnlyMethodCoalescingResult result =
                new NativeOnlyMethodCoalescingCoordinator().run(
                        methods(caller, callee),
                        internalization(caller, callee),
                        implementations,
                        73L);

        assertEquals(1, result.plan().coalescedCount());
        assertEquals(
                Optional.of(caller.methodKey()),
                result.plan().coalescedInto(callee.methodKey()));
        assertFalse(result.methods().containsKey(callee.methodKey()));
        IrMethod mergedCaller = result.methods().get(caller.methodKey());
        assertFalse(hasReference(mergedCaller, callee.methodKey()));
        assertEquals(
                Optional.of(caller.methodKey()),
                result.implementationPlan()
                        .implementationFor(callee.methodKey())
                        .orElseThrow()
                        .coalescedIntoMethodKey());
        assertEquals(
                List.of(caller.methodKey()),
                result.implementationPlan().emittedLlvmImplementations()
                        .stream()
                        .map(NativeMethodImplementation::methodKey)
                        .toList());
        NativeMethodImplementation mergedCallerImplementation = result
                .implementationPlan()
                .implementationFor(caller.methodKey())
                .orElseThrow();
        assertFalse(mergedCallerImplementation.passesJniEnv());
        assertFalse(mergedCallerImplementation.passesOwnerClass());

        var compilation = new NativeLlvmCompiler(
                        new LlvmModuleLowerer(),
                        new LlvmTextEmitter())
                .compile(
                        result.implementationPlan(),
                        result.methods(),
                        LlvmProtectionConfig.disabled(73L));
        String calleeSymbol = result.implementationPlan()
                .implementationFor(callee.methodKey())
                .orElseThrow()
                .llvmFunctionSymbol()
                .orElseThrow();
        assertTrue(compilation.modules().stream()
                .flatMap(module -> module.compiledMethods().stream())
                .noneMatch(method -> method.methodKey().equals(callee.methodKey())));
        assertTrue(compilation.modules().stream()
                .noneMatch(module -> module.llvmText().contains("@" + calleeSymbol)));
        assertTrue(new NativeOnlyMethodCoalescingEmissionVerifier()
                .residuals(
                        result.plan(),
                        result.implementationPlan(),
                        compilation)
                .isEmpty());
        Path generated = workspace.resolve("native/zig-workspace/jni/generated.c");
        Files.createDirectories(generated.getParent());
        Files.writeString(generated, "void caller_only(void) {}\n");
        assertTrue(new NativeOnlyMethodCoalescingEmissionVerifier()
                .workspaceResiduals(
                        workspace,
                        result.plan(),
                        result.implementationPlan(),
                        compilation)
                .isEmpty());
        Files.writeString(generated, "extern void " + calleeSymbol + "(void);\n");
        assertFalse(new NativeOnlyMethodCoalescingEmissionVerifier()
                .workspaceResiduals(
                        workspace,
                        result.plan(),
                        result.implementationPlan(),
                        compilation)
                .isEmpty());
    }

    @Test
    void keepsCallSensitiveCalleeAsStandaloneBody() {
        IrValue callResult = value("%result", IrType.I32);
        IrMethod callee = method(
                "helper",
                "()I",
                IrType.I32,
                List.of(),
                new IrBlock(
                        "entry",
                        List.of(IrInstruction.call(
                                Optional.of(callResult),
                                IrOpcode.CALL_RUNTIME_HELPER,
                                List.of(),
                                "j2ll_rt_test")),
                        IrTerminator.returnValue(callResult)));
        IrMethod caller = caller("entry", callee, 1);

        NativeOnlyMethodCoalescingResult result =
                new NativeOnlyMethodCoalescingCoordinator().run(
                        methods(caller, callee),
                        internalization(caller, callee),
                        implementationPlan(caller, callee, Map.of()),
                        73L);

        assertEquals(0, result.plan().coalescedCount());
        assertTrue(result.methods().containsKey(callee.methodKey()));
        assertTrue(result.plan().decisions().stream().anyMatch(decision ->
                decision.calleeMethodKey().equals(callee.methodKey())
                        && decision.reasonCode().equals(
                                "METHOD_INLINING_CALL_OR_FIELD_SENSITIVE")));
        var coverage = result.protectionReport().coverageFacts().get(0);
        assertEquals(ProtectionApplicability.NOT_APPLICABLE, coverage.applicability());
        assertEquals("SKIPPED", coverage.status());
    }

    @Test
    void reportsValidationFailureAsUnknownInsteadOfNotApplicable() {
        IrValue undefined = value("%undefined", IrType.I32);
        IrMethod invalidCallee = method(
                "invalidHelper",
                "()I",
                IrType.I32,
                List.of(),
                new IrBlock(
                        "entry",
                        List.of(),
                        IrTerminator.returnValue(undefined)));
        IrMethod caller = caller("entry", invalidCallee, 1);

        NativeOnlyMethodCoalescingResult result =
                new NativeOnlyMethodCoalescingCoordinator().run(
                        methods(caller, invalidCallee),
                        internalization(caller, invalidCallee),
                        implementationPlan(caller, invalidCallee, Map.of()),
                        73L);

        assertEquals("FAILED", result.protectionReport().status());
        assertEquals(
                MethodInliningReason.VALIDATION_FAILED,
                result.protectionReport().reasonCode());
        var coverage = result.protectionReport().coverageFacts().get(0);
        assertEquals(ProtectionApplicability.UNKNOWN, coverage.applicability());
        assertFalse(coverage.affected());
        assertEquals("FAILED", coverage.status());
        assertEquals(MethodInliningReason.VALIDATION_FAILED, coverage.reasonCode());
    }

    @Test
    void mergesSameOwnerInstanceSpecialCall() {
        IrValue calleeSelf = value("%calleeSelf", IrType.REFERENCE);
        IrValue calleeInput = value("%calleeInput", IrType.I32);
        IrValue one = value("%one", IrType.I32);
        IrValue sum = value("%sum", IrType.I32);
        IrMethod callee = method(
                "instanceHelper",
                "(I)I",
                IrType.I32,
                List.of(calleeSelf, calleeInput),
                new IrBlock(
                        "entry",
                        List.of(
                                IrInstruction.constInt(one, 1),
                                IrInstruction.binary(
                                        sum,
                                        IrOpcode.ADD_I32,
                                        calleeInput,
                                        one)),
                        IrTerminator.returnValue(sum)));
        IrValue callerSelf = value("%callerSelf", IrType.REFERENCE);
        IrValue callerInput = value("%callerInput", IrType.I32);
        IrValue callResult = value("%call", IrType.I32);
        IrMethod caller = method(
                "instanceEntry",
                "(I)I",
                IrType.I32,
                List.of(callerSelf, callerInput),
                new IrBlock(
                        "entry",
                        List.of(IrInstruction.call(
                                Optional.of(callResult),
                                IrOpcode.CALL_SPECIAL,
                                List.of(callerSelf, callerInput),
                                callee.methodKey())),
                        IrTerminator.returnValue(callResult)));
        NativeImplementationPlan implementations = new NativeImplementationPlan(
                List.of(
                        implementation(
                                caller,
                                MethodRewriteStrategy.NATIVE_ORIGINAL,
                                List.of(callee.methodKey()),
                                false),
                        implementation(
                                callee,
                                MethodRewriteStrategy.INTERNAL_NATIVE_ONLY,
                                List.of(),
                                false)),
                Map.of(),
                Map.of());
        NativeMethodInternalizationPlan internalization =
                new NativeMethodInternalizationPlan(
                        true,
                        WholeProgramAnalysisScope.DECLARED_CLOSED_WORLD,
                        List.of(internalizationDecision(callee, caller, false)));

        NativeOnlyMethodCoalescingResult result =
                new NativeOnlyMethodCoalescingCoordinator().run(
                        methods(caller, callee),
                        internalization,
                        implementations,
                        73L);

        assertEquals(1, result.plan().coalescedCount());
        assertFalse(result.methods().containsKey(callee.methodKey()));
        assertFalse(hasReference(
                result.methods().get(caller.methodKey()),
                callee.methodKey()));
    }

    @Test
    void mergesMultipleIndependentCalleesIntoTheSameCaller() {
        IrMethod first = increment("firstHelper");
        IrMethod second = increment("secondHelper");
        IrValue input = value("%input", IrType.I32);
        IrValue firstResult = value("%firstResult", IrType.I32);
        IrValue secondResult = value("%secondResult", IrType.I32);
        IrMethod caller = method(
                "combinedEntry",
                "(I)I",
                IrType.I32,
                List.of(input),
                new IrBlock(
                        "entry",
                        List.of(
                                IrInstruction.call(
                                        Optional.of(firstResult),
                                        IrOpcode.CALL_STATIC,
                                        List.of(input),
                                        first.methodKey()),
                                IrInstruction.call(
                                        Optional.of(secondResult),
                                        IrOpcode.CALL_STATIC,
                                        List.of(firstResult),
                                        second.methodKey())),
                        IrTerminator.returnValue(secondResult)));
        NativeImplementationPlan implementations = new NativeImplementationPlan(
                List.of(
                        implementation(
                                caller,
                                MethodRewriteStrategy.NATIVE_ORIGINAL,
                                List.of(first.methodKey(), second.methodKey())),
                        implementation(
                                first,
                                MethodRewriteStrategy.INTERNAL_NATIVE_ONLY,
                                List.of()),
                        implementation(
                                second,
                                MethodRewriteStrategy.INTERNAL_NATIVE_ONLY,
                                List.of())),
                Map.of(),
                Map.of());
        NativeMethodInternalizationPlan internalization =
                new NativeMethodInternalizationPlan(
                        true,
                        WholeProgramAnalysisScope.DECLARED_CLOSED_WORLD,
                        List.of(
                                internalizationDecision(first, caller),
                                internalizationDecision(second, caller)));

        NativeOnlyMethodCoalescingResult result =
                new NativeOnlyMethodCoalescingCoordinator().run(
                        methods(caller, first, second),
                        internalization,
                        implementations,
                        73L);

        assertEquals(2, result.plan().coalescedCount());
        assertEquals(List.of(caller.methodKey()), result.methods().keySet().stream().toList());
        assertTrue(result.implementationPlan()
                .implementationFor(caller.methodKey())
                .orElseThrow()
                .directCallTargets()
                .isEmpty());
        assertEquals(
                List.of(caller.methodKey()),
                result.implementationPlan().emittedLlvmImplementations()
                        .stream()
                        .map(NativeMethodImplementation::methodKey)
                        .toList());
    }

    @Test
    void mergesSingleSitePureCalleeBeyondGeneralInliningSizeLimit() {
        IrValue input = value("%wideInput", IrType.I32);
        java.util.ArrayList<IrInstruction> instructions =
                new java.util.ArrayList<>();
        IrValue current = input;
        for (int index = 0; index < 40; index++) {
            IrValue one = value("%wideOne" + index, IrType.I32);
            IrValue next = value("%wideSum" + index, IrType.I32);
            instructions.add(IrInstruction.constInt(one, 1));
            instructions.add(IrInstruction.binary(
                    next,
                    IrOpcode.ADD_I32,
                    current,
                    one));
            current = next;
        }
        IrMethod callee = method(
                "wideHelper",
                "(I)I",
                IrType.I32,
                List.of(input),
                new IrBlock(
                        "entry",
                        instructions,
                        IrTerminator.returnValue(current)));
        IrMethod caller = caller("wideEntry", callee, 1);

        NativeOnlyMethodCoalescingResult result =
                new NativeOnlyMethodCoalescingCoordinator().run(
                        methods(caller, callee),
                        internalization(caller, callee),
                        implementationPlan(caller, callee, Map.of()),
                        73L);

        assertEquals(1, result.plan().coalescedCount());
        assertFalse(result.methods().containsKey(callee.methodKey()));
        assertFalse(hasReference(
                result.methods().get(caller.methodKey()),
                callee.methodKey()));
    }

    @Test
    void rejectsMultipleSitesAndExistingLocalReferencePlans() {
        IrMethod callee = increment("helper");
        IrMethod twoSites = caller("twoSites", callee, 2);
        NativeOnlyMethodCoalescingResult multiple =
                new NativeOnlyMethodCoalescingCoordinator().run(
                        methods(twoSites, callee),
                        internalization(twoSites, callee),
                        implementationPlan(twoSites, callee, Map.of()),
                        73L);
        assertTrue(multiple.plan().decisions().stream().anyMatch(decision ->
                decision.reasonCode().equals(
                        NativeOnlyMethodCoalescingReason.CALL_SITE_NOT_UNIQUE)));

        IrMethod oneSite = caller("oneSite", callee, 1);
        NativeLocalReferencePlan callerReferences =
                new NativeLocalReferencePlan(
                        oneSite.methodKey(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of());
        NativeOnlyMethodCoalescingResult references =
                new NativeOnlyMethodCoalescingCoordinator().run(
                        methods(oneSite, callee),
                        internalization(oneSite, callee),
                        implementationPlan(
                                oneSite,
                                callee,
                                Map.of(oneSite.methodKey(), callerReferences)),
                        73L);
        assertTrue(references.plan().decisions().stream().anyMatch(decision ->
                decision.reasonCode().equals(
                        NativeOnlyMethodCoalescingReason
                                .LOCAL_REFERENCE_SENSITIVE)));
    }

    @Test
    void mergesFourLayerCandidateChainsIntoOneRootWithoutEmissionResiduals(
            @TempDir Path workspace) throws Exception {
        IrMethod leaf = increment("leaf");
        IrMethod lowerMiddle = caller("lowerMiddle", leaf, 1);
        IrMethod upperMiddle = caller("upperMiddle", lowerMiddle, 1);
        IrMethod entry = caller("entry", upperMiddle, 1);
        NativeImplementationPlan implementations = new NativeImplementationPlan(
                List.of(
                        implementation(
                                entry,
                                MethodRewriteStrategy.NATIVE_ORIGINAL,
                                List.of(upperMiddle.methodKey())),
                        implementation(
                                upperMiddle,
                                MethodRewriteStrategy.INTERNAL_NATIVE_ONLY,
                                List.of(lowerMiddle.methodKey())),
                        implementation(
                                lowerMiddle,
                                MethodRewriteStrategy.INTERNAL_NATIVE_ONLY,
                                List.of(leaf.methodKey())),
                        implementation(
                                leaf,
                                MethodRewriteStrategy.INTERNAL_NATIVE_ONLY,
                                List.of())),
                Map.of(),
                Map.of());
        NativeMethodInternalizationPlan internalization =
                new NativeMethodInternalizationPlan(
                        true,
                        WholeProgramAnalysisScope.DECLARED_CLOSED_WORLD,
                        List.of(
                                internalizationDecision(upperMiddle, entry),
                                internalizationDecision(
                                        lowerMiddle,
                                        upperMiddle),
                                internalizationDecision(leaf, lowerMiddle)));

        NativeOnlyMethodCoalescingResult result =
                new NativeOnlyMethodCoalescingCoordinator().run(
                        methods(entry, upperMiddle, lowerMiddle, leaf),
                        internalization,
                        implementations,
                        73L);

        assertEquals(3, result.plan().coalescedCount());
        assertEquals(
                List.of(entry.methodKey()),
                result.methods().keySet().stream().toList());
        assertEquals(1, result.implementationPlan()
                .emittedLlvmImplementations()
                .size());
        assertEquals(
                Optional.of(entry.methodKey()),
                result.plan().coalescedInto(leaf.methodKey()));
        assertEquals(
                Optional.of(entry.methodKey()),
                result.plan().coalescedInto(lowerMiddle.methodKey()));
        assertEquals(
                Optional.of(entry.methodKey()),
                result.plan().coalescedInto(upperMiddle.methodKey()));
        assertFalse(hasReference(
                result.methods().get(entry.methodKey()),
                leaf.methodKey()));
        assertFalse(hasReference(
                result.methods().get(entry.methodKey()),
                lowerMiddle.methodKey()));
        assertFalse(hasReference(
                result.methods().get(entry.methodKey()),
                upperMiddle.methodKey()));

        var compilation = new NativeLlvmCompiler(
                        new LlvmModuleLowerer(),
                        new LlvmTextEmitter())
                .compile(
                        result.implementationPlan(),
                        result.methods(),
                        LlvmProtectionConfig.disabled(73L));
        NativeOnlyMethodCoalescingEmissionVerifier verifier =
                new NativeOnlyMethodCoalescingEmissionVerifier();
        assertTrue(verifier.residuals(
                result.plan(),
                result.implementationPlan(),
                compilation).isEmpty());
        Path generated = workspace.resolve(
                "native/zig-workspace/jni/chain.c");
        Files.createDirectories(generated.getParent());
        Files.writeString(generated, "void root_only(void) {}\n");
        assertTrue(verifier.workspaceResiduals(
                workspace,
                result.plan(),
                result.implementationPlan(),
                compilation).isEmpty());
    }

    @Test
    void keepsCalleeWhenItsOnlyCallerUsesAnInitializerPlan() {
        IrMethod callee = method(
                "initializerHelper",
                "()V",
                IrType.VOID,
                List.of(),
                new IrBlock(
                        "entry",
                        List.of(),
                        IrTerminator.returnVoid()));
        IrMethod initializer = method(
                "<clinit>",
                "()V",
                IrType.VOID,
                List.of(),
                new IrBlock(
                        "entry",
                        List.of(IrInstruction.call(
                                Optional.empty(),
                                IrOpcode.CALL_STATIC,
                                List.of(),
                                callee.methodKey())),
                        IrTerminator.returnVoid()));
        NativeMethodImplementation callerBase = implementation(
                initializer,
                MethodRewriteStrategy.CLASS_INITIALIZER_STUB,
                List.of(callee.methodKey()));
        NativeMethodImplementation initializerImplementation =
                withInitializerPlan(
                        callerBase,
                        InitializerImplementationPlan.classInitializer(
                                initializer));
        NativeImplementationPlan implementations = new NativeImplementationPlan(
                List.of(
                        initializerImplementation,
                        implementation(
                                callee,
                                MethodRewriteStrategy.INTERNAL_NATIVE_ONLY,
                                List.of())),
                Map.of(),
                Map.of());

        NativeOnlyMethodCoalescingResult result =
                new NativeOnlyMethodCoalescingCoordinator().run(
                        methods(initializer, callee),
                        internalization(initializer, callee),
                        implementations,
                        73L);

        assertEquals(0, result.plan().coalescedCount());
        assertTrue(result.methods().containsKey(callee.methodKey()));
        assertTrue(result.plan().decisions().stream().anyMatch(decision ->
                decision.calleeMethodKey().equals(callee.methodKey())
                        && decision.reasonCode().equals(
                                NativeOnlyMethodCoalescingReason
                                        .CALLER_INITIALIZER_PLAN_UNSUPPORTED)));
    }

    private NativeImplementationPlan implementationPlan(
            IrMethod caller,
            IrMethod callee,
            Map<String, NativeLocalReferencePlan> localReferences) {
        NativeMethodImplementation callerImplementation = implementation(
                caller,
                MethodRewriteStrategy.NATIVE_ORIGINAL,
                List.of(callee.methodKey()));
        NativeMethodImplementation calleeImplementation = implementation(
                callee,
                MethodRewriteStrategy.INTERNAL_NATIVE_ONLY,
                List.of());
        return new NativeImplementationPlan(
                List.of(callerImplementation, calleeImplementation),
                Map.of(),
                localReferences);
    }

    private NativeMethodImplementation implementation(
            IrMethod method,
            MethodRewriteStrategy strategy,
            List<String> directTargets) {
        return implementation(method, strategy, directTargets, true);
    }

    private NativeMethodImplementation implementation(
            IrMethod method,
            MethodRewriteStrategy strategy,
            List<String> directTargets,
            boolean staticMethod) {
        int access = strategy == MethodRewriteStrategy.INTERNAL_NATIVE_ONLY
                ? AccessFlags.PRIVATE
                : AccessFlags.PUBLIC;
        if (staticMethod) {
            access |= AccessFlags.STATIC;
        }
        ParsedMethod parsed = parsed(method, access);
        MethodRewriteDecision decision = new MethodRewriteDecision(
                parsed,
                strategy,
                method.owner(),
                Optional.empty(),
                "test");
        return new NativeMethodImplementation(
                new NativeRegistrationEntry(
                        method.owner(),
                        method.name(),
                        method.descriptor(),
                        "j2ll_test_" + method.name()),
                decision,
                NativeImplementationPath.LLVM_NATIVE_PATH,
                Optional.of(new LlvmNameMangler().functionName(method)),
                "test",
                !directTargets.isEmpty(),
                !directTargets.isEmpty(),
                List.of(),
                directTargets,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Optional.of(method));
    }

    private NativeMethodImplementation withInitializerPlan(
            NativeMethodImplementation implementation,
            InitializerImplementationPlan initializerPlan) {
        return new NativeMethodImplementation(
                implementation.entry(),
                implementation.decision(),
                implementation.path(),
                implementation.llvmFunctionSymbol(),
                implementation.reasonCode(),
                implementation.passesJniEnv(),
                implementation.passesOwnerClass(),
                implementation.fieldKeys(),
                implementation.directCallTargets(),
                implementation.allocationKeys(),
                implementation.typeCheckKeys(),
                implementation.classObjectKeys(),
                implementation.runtimeMetadataKeys(),
                implementation.constructorCallKeys(),
                implementation.staticCallKeys(),
                implementation.dispatchKeys(),
                implementation.stringHelperSymbols(),
                implementation.templateIrMethod(),
                Optional.of(initializerPlan),
                implementation.coalescedIntoMethodKey());
    }

    private NativeMethodInternalizationPlan internalization(
            IrMethod caller,
            IrMethod callee) {
        return new NativeMethodInternalizationPlan(
                true,
                WholeProgramAnalysisScope.DECLARED_CLOSED_WORLD,
                List.of(internalizationDecision(callee, caller)));
    }

    private NativeMethodInternalizationDecision internalizationDecision(
            IrMethod callee,
            IrMethod caller) {
        return internalizationDecision(callee, caller, true);
    }

    private NativeMethodInternalizationDecision internalizationDecision(
            IrMethod callee,
            IrMethod caller,
            boolean staticMethod) {
        return new NativeMethodInternalizationDecision(
                NativeMethodId.fromMethodKey(callee.methodKey()),
                NativeMethodInternalizationStatus.INTERNALIZED,
                staticMethod,
                "private",
                List.of(caller.methodKey()),
                List.of(NativeMethodInternalizationReason
                        .METHOD_INTERNALIZATION_ELIGIBLE));
    }

    private IrMethod increment(String name) {
        IrValue input = value("%input", IrType.I32);
        IrValue one = value("%one", IrType.I32);
        IrValue result = value("%sum", IrType.I32);
        return method(
                name,
                "(I)I",
                IrType.I32,
                List.of(input),
                new IrBlock(
                        "entry",
                        List.of(
                                IrInstruction.constInt(one, 1),
                                IrInstruction.binary(
                                        result,
                                        IrOpcode.ADD_I32,
                                        input,
                                        one)),
                        IrTerminator.returnValue(result)));
    }

    private IrMethod caller(String name, IrMethod callee, int callCount) {
        IrValue input = callee.parameters().isEmpty()
                ? null
                : value("%input_" + name, IrType.I32);
        java.util.ArrayList<IrInstruction> instructions = new java.util.ArrayList<>();
        IrValue result = null;
        for (int index = 0; index < callCount; index++) {
            result = value("%call" + index, callee.returnType());
            instructions.add(IrInstruction.call(
                    Optional.of(result),
                    IrOpcode.CALL_STATIC,
                    input == null ? List.of() : List.of(input),
                    callee.methodKey()));
        }
        return method(
                name,
                callee.descriptor(),
                callee.returnType(),
                input == null ? List.of() : List.of(input),
                new IrBlock(
                        "entry",
                        instructions,
                        IrTerminator.returnValue(result)));
    }

    private ParsedMethod parsed(IrMethod method, int access) {
        return new ParsedMethod(
                method.owner(),
                method.name(),
                method.descriptor(),
                new AccessFlags(access),
                List.of(),
                List.of(),
                List.of(),
                true,
                1,
                2,
                new MethodNode(
                        Opcodes.ASM9,
                        access,
                        method.name(),
                        method.descriptor(),
                        null,
                        null));
    }

    private LinkedHashMap<String, IrMethod> methods(IrMethod... methods) {
        LinkedHashMap<String, IrMethod> result = new LinkedHashMap<>();
        for (IrMethod method : methods) {
            result.put(method.methodKey(), method);
        }
        return result;
    }

    private IrMethod method(
            String name,
            String descriptor,
            IrType returnType,
            List<IrValue> parameters,
            IrBlock... blocks) {
        return new IrMethod(
                "pkg/Owner",
                name,
                descriptor,
                returnType,
                parameters,
                List.of(blocks));
    }

    private IrValue value(String name, IrType type) {
        return new IrValue(name, type);
    }

    private boolean hasReference(IrMethod method, String target) {
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(instruction -> instruction.symbol()
                        .filter(target::equals)
                        .isPresent());
    }
}
