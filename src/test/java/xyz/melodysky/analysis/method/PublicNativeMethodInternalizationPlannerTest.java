package xyz.melodysky.analysis.method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.ACC_ABSTRACT;
import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_INTERFACE;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static xyz.melodysky.analysis.method.NativeMethodInternalizationTestFixtures.callGraph;
import static xyz.melodysky.analysis.method.NativeMethodInternalizationTestFixtures.hierarchy;
import static xyz.melodysky.analysis.method.NativeMethodInternalizationTestFixtures.implementation;
import static xyz.melodysky.analysis.method.NativeMethodInternalizationTestFixtures.known;
import static xyz.melodysky.analysis.method.NativeMethodInternalizationTestFixtures.method;
import static xyz.melodysky.analysis.method.NativeMethodInternalizationTestFixtures.noReflection;
import static xyz.melodysky.analysis.method.NativeMethodInternalizationTestFixtures.program;
import static xyz.melodysky.analysis.method.NativeMethodInternalizationTestFixtures.type;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import xyz.melodysky.analysis.callgraph.CallGraph;
import xyz.melodysky.analysis.callgraph.CallTarget;
import xyz.melodysky.analysis.callgraph.InvokeKind;
import xyz.melodysky.analysis.reflection.ReflectionMethodKind;
import xyz.melodysky.analysis.reflection.ReflectionMethodTarget;
import xyz.melodysky.analysis.reflection.ReflectionPlan;
import xyz.melodysky.analysis.reflection.ReflectionUnsupportedSite;
import xyz.melodysky.analysis.world.WholeProgramAnalysisScope;
import xyz.melodysky.frontend.classfile.ParsedProgram;
import xyz.melodysky.toolchain.NativeImplementationPath;
import xyz.melodysky.toolchain.NativeImplementationPlan;
import xyz.melodysky.toolchain.NativeMethodImplementation;

class PublicNativeMethodInternalizationPlannerTest {
    private static final String OWNER = "fixture/PublicOwner";
    private static final String CALLER = "fixture/PublicCaller";
    private static final String TARGET_KEY = OWNER + "#target!()I";
    private static final String CALLER_KEY = CALLER + "#caller!()I";

    @Test
    void legacyOverloadKeepsPublicMethodWithoutExplicitAllowlist() {
        ParsedProgram program = publicStaticProgram();
        NativeImplementationPlan implementations = implementations(
                program,
                TARGET_KEY,
                CALLER_KEY,
                List.of(),
                List.of(TARGET_KEY),
                List.of());

        NativeMethodInternalizationPlan plan =
                new NativeMethodInternalizationPlanner().plan(
                        true,
                        WholeProgramAnalysisScope.DECLARED_CLOSED_WORLD,
                        program,
                        hierarchy(program),
                        exactCall(CALLER_KEY, InvokeKind.STATIC, TARGET_KEY),
                        noReflection(),
                        Set.of(),
                        implementations);

        NativeMethodInternalizationDecision decision =
                plan.decisionFor(TARGET_KEY).orElseThrow();
        assertRejected(
                decision,
                NativeMethodInternalizationReason
                        .METHOD_INTERNALIZATION_PUBLIC_NOT_ALLOWLISTED);
        assertEquals("public", decision.access());
    }

    @Test
    void approvesAllowlistedPublicStaticMethodWithFinalLlvmCaller() {
        ParsedProgram program = publicStaticProgram();

        NativeMethodInternalizationDecision decision = plan(
                        program,
                        exactCall(CALLER_KEY, InvokeKind.STATIC, TARGET_KEY),
                        noReflection(),
                        TARGET_KEY,
                        CALLER_KEY,
                        WholeProgramAnalysisScope.DECLARED_CLOSED_WORLD,
                        List.of(),
                        List.of(TARGET_KEY),
                        List.of())
                .decisionFor(TARGET_KEY)
                .orElseThrow();

        assertApproved(decision);
        assertTrue(decision.staticMethod());
        assertEquals("public", decision.access());
    }

    @Test
    void approvesAllowlistedPublicStaticWithCurrentJarAuthorization() {
        ParsedProgram program = publicStaticProgram();

        NativeMethodInternalizationDecision decision = plan(
                        program,
                        exactCall(CALLER_KEY, InvokeKind.STATIC, TARGET_KEY),
                        noReflection(),
                        TARGET_KEY,
                        CALLER_KEY,
                        WholeProgramAnalysisScope.CURRENT_JAR_ONLY_USER_APPROVED,
                        List.of(),
                        List.of(TARGET_KEY),
                        List.of())
                .decisionFor(TARGET_KEY)
                .orElseThrow();

        assertApproved(decision);
    }

    @Test
    void keepsLauncherAndAgentEntryPoints() {
        assertExternalEntryPointKept(
                "main",
                "([Ljava/lang/String;)V");
        assertExternalEntryPointKept(
                "premain",
                "(Ljava/lang/String;Ljava/lang/instrument/Instrumentation;)V");
        assertExternalEntryPointKept(
                "agentmain",
                "(Ljava/lang/String;)V");
    }

    @Test
    void unresolvedReflectionSiteDoesNotBlanketRejectPublicCandidate() {
        ParsedProgram program = publicStaticProgram();
        ReflectionPlan reflectionPlan = new ReflectionPlan(
                List.of(),
                List.of(),
                List.of(),
                List.of(new ReflectionUnsupportedSite(
                        CALLER,
                        "caller",
                        "()I",
                        0,
                        "REFLECTION_UNSUPPORTED_SCAN",
                        "reflection member scan is not statically enumerated")));

        NativeMethodInternalizationDecision decision = plan(
                        program,
                        exactCall(CALLER_KEY, InvokeKind.STATIC, TARGET_KEY),
                        reflectionPlan,
                        TARGET_KEY,
                        CALLER_KEY,
                        WholeProgramAnalysisScope.DECLARED_CLOSED_WORLD,
                        List.of(),
                        List.of(TARGET_KEY),
                        List.of())
                .decisionFor(TARGET_KEY)
                .orElseThrow();

        assertApproved(decision);
    }

    @Test
    void resolvedReflectionObserverStillKeepsPublicCandidate() {
        ParsedProgram program = publicStaticProgram();
        ReflectionPlan reflectionPlan = new ReflectionPlan(
                List.of(),
                List.of(new ReflectionMethodTarget(
                        OWNER,
                        "target",
                        "()I",
                        ReflectionMethodKind.REFLECTIVE_INVOKE,
                        false,
                        CALLER_KEY + "@0")),
                List.of(),
                List.of());

        NativeMethodInternalizationDecision decision = plan(
                        program,
                        exactCall(CALLER_KEY, InvokeKind.STATIC, TARGET_KEY),
                        reflectionPlan,
                        TARGET_KEY,
                        CALLER_KEY,
                        WholeProgramAnalysisScope.DECLARED_CLOSED_WORLD,
                        List.of(),
                        List.of(TARGET_KEY),
                        List.of())
                .decisionFor(TARGET_KEY)
                .orElseThrow();

        assertRejected(
                decision,
                NativeMethodInternalizationReason
                        .METHOD_INTERNALIZATION_REFLECTION_OBSERVER);
    }

    @Test
    void resolvedDeclaredMethodLookupKeepsPublicCandidateWithoutInvoke() {
        ParsedProgram program = publicStaticProgram();
        ReflectionPlan reflectionPlan = new ReflectionPlan(
                List.of(),
                List.of(new ReflectionMethodTarget(
                        OWNER,
                        "target",
                        "()I",
                        ReflectionMethodKind.DECLARED_METHOD,
                        false,
                        CALLER_KEY + "@0")),
                List.of(),
                List.of());

        NativeMethodInternalizationDecision decision = plan(
                        program,
                        exactCall(CALLER_KEY, InvokeKind.STATIC, TARGET_KEY),
                        reflectionPlan,
                        TARGET_KEY,
                        CALLER_KEY,
                        WholeProgramAnalysisScope.DECLARED_CLOSED_WORLD,
                        List.of(),
                        List.of(TARGET_KEY),
                        List.of())
                .decisionFor(TARGET_KEY)
                .orElseThrow();

        assertRejected(
                decision,
                NativeMethodInternalizationReason
                        .METHOD_INTERNALIZATION_REFLECTION_OBSERVER);
    }

    @Test
    void approvesSameOwnerPublicFinalVirtualMethodWithExactTarget() {
        ParsedProgram program = publicInstanceProgram(
                ACC_PUBLIC,
                ACC_PUBLIC | ACC_FINAL);
        String callerKey = OWNER + "#caller!()I";

        NativeMethodInternalizationDecision decision = plan(
                        program,
                        exactCall(callerKey, InvokeKind.VIRTUAL, TARGET_KEY),
                        noReflection(),
                        TARGET_KEY,
                        callerKey,
                        WholeProgramAnalysisScope.DECLARED_CLOSED_WORLD,
                        List.of(),
                        List.of(),
                        List.of(TARGET_KEY))
                .decisionFor(TARGET_KEY)
                .orElseThrow();

        assertApproved(decision);
        assertFalse(decision.staticMethod());
    }

    @Test
    void approvesExactPublicVirtualMethodOnFinalOwner() {
        ParsedProgram program = publicInstanceProgram(
                ACC_PUBLIC | ACC_FINAL,
                ACC_PUBLIC);
        String callerKey = OWNER + "#caller!()I";

        NativeMethodInternalizationDecision decision = plan(
                        program,
                        exactCall(callerKey, InvokeKind.VIRTUAL, TARGET_KEY),
                        noReflection(),
                        TARGET_KEY,
                        callerKey,
                        WholeProgramAnalysisScope.DECLARED_CLOSED_WORLD,
                        List.of(),
                        List.of(),
                        List.of(TARGET_KEY))
                .decisionFor(TARGET_KEY)
                .orElseThrow();

        assertApproved(decision);
    }

    @Test
    void approvesSameOwnerExactSpecialCallToPublicMethod() {
        ParsedProgram program = publicInstanceProgram(
                ACC_PUBLIC,
                ACC_PUBLIC);
        String callerKey = OWNER + "#caller!()I";

        NativeMethodInternalizationDecision decision = plan(
                        program,
                        exactCall(callerKey, InvokeKind.SPECIAL, TARGET_KEY),
                        noReflection(),
                        TARGET_KEY,
                        callerKey,
                        WholeProgramAnalysisScope.DECLARED_CLOSED_WORLD,
                        List.of(TARGET_KEY),
                        List.of(),
                        List.of())
                .decisionFor(TARGET_KEY)
                .orElseThrow();

        assertApproved(decision);
    }

    @Test
    void approvesExactNonFinalPublicInstanceMethodInDeclaredClosedWorld() {
        ParsedProgram program = publicInstanceProgram(
                ACC_PUBLIC,
                ACC_PUBLIC);
        String callerKey = OWNER + "#caller!()I";

        NativeMethodInternalizationDecision decision = plan(
                        program,
                        exactCall(callerKey, InvokeKind.VIRTUAL, TARGET_KEY),
                        noReflection(),
                        TARGET_KEY,
                        callerKey,
                        WholeProgramAnalysisScope.DECLARED_CLOSED_WORLD,
                        List.of(),
                        List.of(),
                        List.of(TARGET_KEY))
                .decisionFor(TARGET_KEY)
                .orElseThrow();

        assertApproved(decision);
    }

    @Test
    void approvesExactPublicInstanceMethodParticipatingInOverrideSlot() {
        String base = "fixture/PublicBase";
        ParsedProgram program = program(
                type(
                        base,
                        "java/lang/Object",
                        ACC_PUBLIC,
                        method(ACC_PUBLIC, "target", "()I")),
                type(
                        OWNER,
                        base,
                        ACC_PUBLIC,
                        method(ACC_PUBLIC, "target", "()I"),
                        method(ACC_PUBLIC, "caller", "()I")));
        String callerKey = OWNER + "#caller!()I";

        NativeMethodInternalizationDecision decision = plan(
                        program,
                        exactCall(callerKey, InvokeKind.VIRTUAL, TARGET_KEY),
                        noReflection(),
                        TARGET_KEY,
                        callerKey,
                        WholeProgramAnalysisScope.DECLARED_CLOSED_WORLD,
                        List.of(),
                        List.of(),
                        List.of(TARGET_KEY))
                .decisionFor(TARGET_KEY)
                .orElseThrow();

        assertApproved(decision);
    }

    @Test
    void publicInstanceMethodStillRequiresDeclaredClosedWorld() {
        ParsedProgram program = publicInstanceProgram(
                ACC_PUBLIC,
                ACC_PUBLIC);
        String callerKey = OWNER + "#caller!()I";

        NativeMethodInternalizationDecision decision = plan(
                        program,
                        exactCall(callerKey, InvokeKind.VIRTUAL, TARGET_KEY),
                        noReflection(),
                        TARGET_KEY,
                        callerKey,
                        WholeProgramAnalysisScope.CURRENT_JAR_ONLY_USER_APPROVED,
                        List.of(),
                        List.of(),
                        List.of(TARGET_KEY))
                .decisionFor(TARGET_KEY)
                .orElseThrow();

        assertRejected(
                decision,
                NativeMethodInternalizationReason
                        .METHOD_INTERNALIZATION_PUBLIC_INSTANCE_REQUIRES_DECLARED_CLOSED_WORLD);
    }

    @Test
    void incompleteHierarchyKeepsPublicInstanceWithoutBlockingPublicStatic() {
        String missingSuper = "fixture/MissingSuper";
        String instanceCallerKey = OWNER + "#caller!()I";
        ParsedProgram instanceProgram = program(type(
                OWNER,
                missingSuper,
                ACC_PUBLIC,
                method(ACC_PUBLIC, "target", "()I"),
                method(ACC_PUBLIC, "caller", "()I")));

        assertFalse(hierarchy(instanceProgram).isComplete());
        NativeMethodInternalizationDecision instanceDecision = plan(
                        instanceProgram,
                        exactCall(
                                instanceCallerKey,
                                InvokeKind.VIRTUAL,
                                TARGET_KEY),
                        noReflection(),
                        TARGET_KEY,
                        instanceCallerKey,
                        WholeProgramAnalysisScope.DECLARED_CLOSED_WORLD,
                        List.of(),
                        List.of(),
                        List.of(TARGET_KEY))
                .decisionFor(TARGET_KEY)
                .orElseThrow();

        assertRejected(
                instanceDecision,
                NativeMethodInternalizationReason
                        .METHOD_INTERNALIZATION_PUBLIC_INSTANCE_ANALYSIS_WORLD_INCOMPLETE);

        ParsedProgram staticProgram = program(
                type(
                        OWNER,
                        missingSuper,
                        ACC_PUBLIC,
                        method(ACC_PUBLIC | ACC_STATIC, "target", "()I")),
                type(
                        CALLER,
                        "java/lang/Object",
                        ACC_PUBLIC,
                        method(ACC_PUBLIC | ACC_STATIC, "caller", "()I")));
        NativeMethodInternalizationDecision staticDecision = plan(
                        staticProgram,
                        exactCall(
                                CALLER_KEY,
                                InvokeKind.STATIC,
                                TARGET_KEY),
                        noReflection(),
                        TARGET_KEY,
                        CALLER_KEY,
                        WholeProgramAnalysisScope.DECLARED_CLOSED_WORLD,
                        List.of(),
                        List.of(TARGET_KEY),
                        List.of())
                .decisionFor(TARGET_KEY)
                .orElseThrow();

        assertApproved(staticDecision);
    }

    @Test
    void rejectsPublicInterfaceMethod() {
        String owner = "fixture/PublicApi";
        String targetKey = owner + "#target!()I";
        String callerKey = owner + "#caller!()I";
        ParsedProgram program = program(type(
                owner,
                "java/lang/Object",
                ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT,
                method(ACC_PUBLIC, "target", "()I"),
                method(ACC_PUBLIC, "caller", "()I")));

        NativeMethodInternalizationDecision decision = plan(
                        program,
                        exactCall(callerKey, InvokeKind.VIRTUAL, targetKey),
                        noReflection(),
                        targetKey,
                        callerKey,
                        WholeProgramAnalysisScope.DECLARED_CLOSED_WORLD,
                        List.of(),
                        List.of(),
                        List.of(targetKey))
                .decisionFor(targetKey)
                .orElseThrow();

        assertRejected(
                decision,
                NativeMethodInternalizationReason
                        .METHOD_INTERNALIZATION_PUBLIC_INTERFACE_METHOD);
    }

    @Test
    void rejectsCrossOwnerPublicInstanceCaller() {
        ParsedProgram program = program(
                type(
                        OWNER,
                        "java/lang/Object",
                        ACC_PUBLIC,
                        method(ACC_PUBLIC | ACC_FINAL, "target", "()I")),
                type(
                        CALLER,
                        "java/lang/Object",
                        ACC_PUBLIC,
                        method(ACC_PUBLIC, "caller", "()I")));

        NativeMethodInternalizationDecision decision = plan(
                        program,
                        exactCall(CALLER_KEY, InvokeKind.VIRTUAL, TARGET_KEY),
                        noReflection(),
                        TARGET_KEY,
                        CALLER_KEY,
                        WholeProgramAnalysisScope.DECLARED_CLOSED_WORLD,
                        List.of(),
                        List.of(),
                        List.of(TARGET_KEY))
                .decisionFor(TARGET_KEY)
                .orElseThrow();

        assertRejected(
                decision,
                NativeMethodInternalizationReason
                        .METHOD_INTERNALIZATION_CROSS_OWNER_INSTANCE_CALL);
    }

    @Test
    void rejectsNonExactPublicVirtualTarget() {
        ParsedProgram program = publicInstanceProgram(
                ACC_PUBLIC,
                ACC_PUBLIC | ACC_FINAL);
        String callerKey = OWNER + "#caller!()I";
        CallGraph graph = callGraph(
                callerKey,
                InvokeKind.VIRTUAL,
                TARGET_KEY,
                List.of(CallTarget.unknownExternal("TEST_UNKNOWN_TARGET")));

        NativeMethodInternalizationDecision decision = plan(
                        program,
                        graph,
                        noReflection(),
                        TARGET_KEY,
                        callerKey,
                        WholeProgramAnalysisScope.DECLARED_CLOSED_WORLD,
                        List.of(),
                        List.of(),
                        List.of(TARGET_KEY))
                .decisionFor(TARGET_KEY)
                .orElseThrow();

        assertRejected(
                decision,
                NativeMethodInternalizationReason
                        .METHOD_INTERNALIZATION_PUBLIC_INSTANCE_TARGET_NOT_EXACT);
    }

    private void assertExternalEntryPointKept(
            String name,
            String descriptor) {
        String owner = "fixture/Entry" + name;
        String targetKey = owner + "#" + name + "!" + descriptor;
        String callerKey = owner + "#caller!()V";
        ParsedProgram program = program(type(
                owner,
                "java/lang/Object",
                ACC_PUBLIC,
                method(ACC_PUBLIC | ACC_STATIC, name, descriptor),
                method(ACC_PUBLIC | ACC_STATIC, "caller", "()V")));

        NativeMethodInternalizationDecision decision = plan(
                        program,
                        exactCall(callerKey, InvokeKind.STATIC, targetKey),
                        noReflection(),
                        targetKey,
                        callerKey,
                        WholeProgramAnalysisScope.DECLARED_CLOSED_WORLD,
                        List.of(),
                        List.of(targetKey),
                        List.of())
                .decisionFor(targetKey)
                .orElseThrow();

        assertRejected(
                decision,
                NativeMethodInternalizationReason
                        .METHOD_INTERNALIZATION_PUBLIC_EXTERNAL_ENTRY_POINT);
    }

    private ParsedProgram publicStaticProgram() {
        return program(
                type(
                        OWNER,
                        "java/lang/Object",
                        ACC_PUBLIC,
                        method(ACC_PUBLIC | ACC_STATIC, "target", "()I")),
                type(
                        CALLER,
                        "java/lang/Object",
                        ACC_PUBLIC,
                        method(ACC_PUBLIC | ACC_STATIC, "caller", "()I")));
    }

    private ParsedProgram publicInstanceProgram(
            int ownerAccess,
            int targetAccess) {
        return program(type(
                OWNER,
                "java/lang/Object",
                ownerAccess,
                method(targetAccess, "target", "()I"),
                method(ACC_PUBLIC, "caller", "()I")));
    }

    private CallGraph exactCall(
            String callerMethodKey,
            InvokeKind kind,
            String targetMethodKey) {
        return callGraph(
                callerMethodKey,
                kind,
                targetMethodKey,
                List.of(known(targetMethodKey)));
    }

    private NativeMethodInternalizationPlan plan(
            ParsedProgram program,
            CallGraph graph,
            ReflectionPlan reflectionPlan,
            String targetMethodKey,
            String callerMethodKey,
            WholeProgramAnalysisScope scope,
            List<String> callerDirectTargets,
            List<String> callerStaticTargets,
            List<String> callerDispatchTargets) {
        NativeImplementationPlan implementations = implementations(
                program,
                targetMethodKey,
                callerMethodKey,
                callerDirectTargets,
                callerStaticTargets,
                callerDispatchTargets);
        return new NativeMethodInternalizationPlanner().plan(
                true,
                scope,
                program,
                hierarchy(program),
                graph,
                reflectionPlan,
                Set.of(),
                implementations,
                Set.of(NativeMethodId.fromMethodKey(targetMethodKey)));
    }

    private NativeImplementationPlan implementations(
            ParsedProgram program,
            String targetMethodKey,
            String callerMethodKey,
            List<String> callerDirectTargets,
            List<String> callerStaticTargets,
            List<String> callerDispatchTargets) {
        ArrayList<NativeMethodImplementation> implementations =
                new ArrayList<>();
        implementations.add(implementation(
                NativeMethodInternalizationTestFixtures.method(
                        program,
                        targetMethodKey),
                NativeImplementationPath.LLVM_NATIVE_PATH,
                List.of(),
                List.of(),
                List.of()));
        implementations.add(implementation(
                NativeMethodInternalizationTestFixtures.method(
                        program,
                        callerMethodKey),
                NativeImplementationPath.LLVM_NATIVE_PATH,
                callerDirectTargets,
                callerStaticTargets,
                callerDispatchTargets));
        return new NativeImplementationPlan(implementations);
    }

    private void assertApproved(
            NativeMethodInternalizationDecision decision) {
        assertTrue(decision.internalized());
        assertEquals(
                List.of(NativeMethodInternalizationReason
                        .METHOD_INTERNALIZATION_ELIGIBLE),
                decision.reasons());
    }

    private void assertRejected(
            NativeMethodInternalizationDecision decision,
            NativeMethodInternalizationReason reason) {
        assertFalse(decision.internalized());
        assertTrue(
                decision.reasons().contains(reason),
                () -> "expected " + reason + " in " + decision.reasons());
    }
}
