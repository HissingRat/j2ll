package xyz.melodysky.analysis.method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_PROTECTED;
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

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import xyz.melodysky.analysis.callgraph.CallGraph;
import xyz.melodysky.analysis.callgraph.CallTarget;
import xyz.melodysky.analysis.callgraph.InvokeKind;
import xyz.melodysky.analysis.reflection.ReflectionMethodKind;
import xyz.melodysky.analysis.reflection.ReflectionMethodTarget;
import xyz.melodysky.analysis.reflection.ReflectionPlan;
import xyz.melodysky.analysis.world.WholeProgramAnalysisScope;
import xyz.melodysky.frontend.classfile.ParsedProgram;
import xyz.melodysky.toolchain.NativeImplementationPath;
import xyz.melodysky.toolchain.NativeImplementationPlan;
import xyz.melodysky.toolchain.NativeMethodImplementation;

class NativeMethodInternalizationPlannerTest {
    private static final String UTILITY = "fixture/Utility";
    private static final String CALLER = "fixture/Caller";
    private static final String TARGET_KEY = UTILITY + "#target!()I";
    private static final String CALLER_KEY = CALLER + "#caller!()I";

    @Test
    void approvesProtectedStaticMethodCalledAcrossOwnersByFinalLlvmCaller() {
        ParsedProgram program = staticCrossOwnerProgram();

        NativeMethodInternalizationDecision decision = plan(
                program,
                exactCall(
                        CALLER_KEY,
                        InvokeKind.STATIC,
                        TARGET_KEY),
                noReflection(),
                TARGET_KEY,
                CALLER_KEY,
                NativeImplementationPath.LLVM_NATIVE_PATH,
                List.of(),
                List.of(TARGET_KEY),
                List.of(),
                true)
                .decisionFor(TARGET_KEY)
                .orElseThrow();

        assertApproved(decision);
        assertTrue(decision.staticMethod());
        assertEquals("protected", decision.access());
        assertEquals(List.of(CALLER_KEY), decision.callerMethodKeys());
    }

    @Test
    void approvesPrivateStaticMethodCalledBySameOwnerLlvmMethod() {
        String owner = "fixture/PrivateOwner";
        String targetKey = owner + "#target!()I";
        String callerKey = owner + "#caller!()I";
        ParsedProgram program = program(type(
                owner,
                "java/lang/Object",
                ACC_PUBLIC,
                method(
                        ACC_PRIVATE | ACC_STATIC,
                        "target",
                        "()I"),
                method(
                        ACC_PUBLIC | ACC_STATIC,
                        "caller",
                        "()I")));

        NativeMethodInternalizationDecision decision = plan(
                program,
                exactCall(
                        callerKey,
                        InvokeKind.STATIC,
                        targetKey),
                noReflection(),
                targetKey,
                callerKey,
                NativeImplementationPath.LLVM_NATIVE_PATH,
                List.of(targetKey),
                List.of(),
                List.of(),
                true)
                .decisionFor(targetKey)
                .orElseThrow();

        assertApproved(decision);
        assertEquals("private", decision.access());
    }

    @Test
    void approvesSameOwnerProtectedFinalVirtualMethodWithExactTarget() {
        String owner = "fixture/InstanceOwner";
        String targetKey = owner + "#target!()I";
        String callerKey = owner + "#caller!()I";
        ParsedProgram program = program(type(
                owner,
                "java/lang/Object",
                ACC_PUBLIC,
                method(
                        ACC_PROTECTED | ACC_FINAL,
                        "target",
                        "()I"),
                method(
                        ACC_PUBLIC,
                        "caller",
                        "()I")));

        NativeMethodInternalizationDecision decision = plan(
                program,
                exactCall(
                        callerKey,
                        InvokeKind.VIRTUAL,
                        targetKey),
                noReflection(),
                targetKey,
                callerKey,
                NativeImplementationPath.LLVM_NATIVE_PATH,
                List.of(),
                List.of(),
                List.of(targetKey),
                true)
                .decisionFor(targetKey)
                .orElseThrow();

        assertApproved(decision);
        assertFalse(decision.staticMethod());
        assertEquals("protected", decision.access());
    }

    @Test
    void rejectsCrossOwnerInstanceCallEvenWhenDispatchIsExact() {
        ParsedProgram program = program(
                type(
                        UTILITY,
                        "java/lang/Object",
                        ACC_PUBLIC,
                        method(
                                ACC_PROTECTED | ACC_FINAL,
                                "target",
                                "()I")),
                type(
                        CALLER,
                        "java/lang/Object",
                        ACC_PUBLIC,
                        method(
                                ACC_PUBLIC,
                                "caller",
                                "()I")));

        NativeMethodInternalizationDecision decision = plan(
                program,
                exactCall(
                        CALLER_KEY,
                        InvokeKind.VIRTUAL,
                        TARGET_KEY),
                noReflection(),
                TARGET_KEY,
                CALLER_KEY,
                NativeImplementationPath.LLVM_NATIVE_PATH,
                List.of(),
                List.of(),
                List.of(TARGET_KEY),
                true)
                .decisionFor(TARGET_KEY)
                .orElseThrow();

        assertRejected(
                decision,
                NativeMethodInternalizationReason
                        .METHOD_INTERNALIZATION_CROSS_OWNER_INSTANCE_CALL);
    }

    @Test
    void rejectsVirtualMethodWithInScopeOverride() {
        String owner = "fixture/Base";
        String child = "fixture/Child";
        String targetKey = owner + "#target!()I";
        String callerKey = owner + "#caller!()I";
        String overrideKey = child + "#target!()I";
        ParsedProgram program = program(
                type(
                        owner,
                        "java/lang/Object",
                        ACC_PUBLIC,
                        method(
                                ACC_PROTECTED,
                                "target",
                                "()I"),
                        method(
                                ACC_PUBLIC,
                                "caller",
                                "()I")),
                type(
                        child,
                        owner,
                        ACC_PUBLIC,
                        method(
                                ACC_PROTECTED,
                                "target",
                                "()I")));
        CallGraph graph = callGraph(
                callerKey,
                InvokeKind.VIRTUAL,
                targetKey,
                List.of(
                        known(targetKey),
                        known(overrideKey)));

        NativeMethodInternalizationDecision decision = plan(
                program,
                graph,
                noReflection(),
                targetKey,
                callerKey,
                NativeImplementationPath.LLVM_NATIVE_PATH,
                List.of(),
                List.of(),
                List.of(targetKey),
                true)
                .decisionFor(targetKey)
                .orElseThrow();

        assertRejected(
                decision,
                NativeMethodInternalizationReason
                        .METHOD_INTERNALIZATION_VIRTUAL_DISPATCH_NOT_EXACT);
    }

    @Test
    void rejectsCallerWithoutFinalNativeImplementation() {
        ParsedProgram program = staticCrossOwnerProgram();

        NativeMethodInternalizationDecision decision = plan(
                program,
                exactCall(
                        CALLER_KEY,
                        InvokeKind.STATIC,
                        TARGET_KEY),
                noReflection(),
                TARGET_KEY,
                CALLER_KEY,
                NativeImplementationPath.LLVM_NATIVE_PATH,
                List.of(),
                List.of(TARGET_KEY),
                List.of(),
                false)
                .decisionFor(TARGET_KEY)
                .orElseThrow();

        assertRejected(
                decision,
                NativeMethodInternalizationReason
                        .METHOD_INTERNALIZATION_CALLER_NOT_NATIVE_LOWERED);
    }

    @Test
    void rejectsCallerWhoseFinalPathIsNotLlvm() {
        ParsedProgram program = staticCrossOwnerProgram();

        NativeMethodInternalizationDecision decision = plan(
                program,
                exactCall(
                        CALLER_KEY,
                        InvokeKind.STATIC,
                        TARGET_KEY),
                noReflection(),
                TARGET_KEY,
                CALLER_KEY,
                NativeImplementationPath.TEMPLATE_JNI_PATH,
                List.of(),
                List.of(TARGET_KEY),
                List.of(),
                true)
                .decisionFor(TARGET_KEY)
                .orElseThrow();

        assertRejected(
                decision,
                NativeMethodInternalizationReason
                        .METHOD_INTERNALIZATION_CALLER_PATH_NOT_LLVM);
    }

    @Test
    void rejectsDirectLdcMethodHandleReference() {
        assertMethodReferenceRejected(ReferenceKind.LDC_HANDLE);
    }

    @Test
    void rejectsInvokeDynamicBootstrapMethodHandleReference() {
        assertMethodReferenceRejected(ReferenceKind.INVOKEDYNAMIC);
    }

    @Test
    void rejectsConstantDynamicBootstrapMethodHandleReference() {
        assertMethodReferenceRejected(ReferenceKind.CONSTANT_DYNAMIC);
    }

    @Test
    void rejectsResolvedReflectionTarget() {
        ParsedProgram program = staticCrossOwnerProgram();
        ReflectionPlan reflectionPlan = new ReflectionPlan(
                List.of(),
                List.of(new ReflectionMethodTarget(
                        UTILITY,
                        "target",
                        "()I",
                        ReflectionMethodKind.REFLECTIVE_INVOKE,
                        false,
                        "fixture/Reflector#invoke!()V@0")),
                List.of(),
                List.of());

        NativeMethodInternalizationDecision decision = plan(
                program,
                exactCall(
                        CALLER_KEY,
                        InvokeKind.STATIC,
                        TARGET_KEY),
                reflectionPlan,
                TARGET_KEY,
                CALLER_KEY,
                NativeImplementationPath.LLVM_NATIVE_PATH,
                List.of(),
                List.of(TARGET_KEY),
                List.of(),
                true)
                .decisionFor(TARGET_KEY)
                .orElseThrow();

        assertRejected(
                decision,
                NativeMethodInternalizationReason
                        .METHOD_INTERNALIZATION_REFLECTION_OBSERVER);
    }

    private void assertMethodReferenceRejected(ReferenceKind kind) {
        Handle targetHandle = new Handle(
                Opcodes.H_INVOKESTATIC,
                UTILITY,
                "target",
                "()I",
                false);
        ParsedProgram program = program(
                type(
                        UTILITY,
                        "java/lang/Object",
                        ACC_PUBLIC,
                        method(
                                ACC_PROTECTED | ACC_STATIC,
                                "target",
                                "()I")),
                type(
                        CALLER,
                        "java/lang/Object",
                        ACC_PUBLIC,
                        method(
                                ACC_PUBLIC | ACC_STATIC,
                                "caller",
                                "()I")),
                type(
                        "fixture/Observer",
                        "java/lang/Object",
                        ACC_PUBLIC,
                        method(
                                ACC_PUBLIC | ACC_STATIC,
                                "observe",
                                "()V",
                                visitor -> emitReference(
                                        visitor,
                                        kind,
                                        targetHandle))));

        NativeMethodInternalizationDecision decision = plan(
                program,
                exactCall(
                        CALLER_KEY,
                        InvokeKind.STATIC,
                        TARGET_KEY),
                noReflection(),
                TARGET_KEY,
                CALLER_KEY,
                NativeImplementationPath.LLVM_NATIVE_PATH,
                List.of(),
                List.of(TARGET_KEY),
                List.of(),
                true)
                .decisionFor(TARGET_KEY)
                .orElseThrow();

        assertRejected(
                decision,
                NativeMethodInternalizationReason
                        .METHOD_INTERNALIZATION_METHOD_HANDLE_REFERENCE);
    }

    private static void emitReference(
            org.objectweb.asm.MethodVisitor visitor,
            ReferenceKind kind,
            Handle targetHandle) {
        switch (kind) {
            case LDC_HANDLE -> visitor.visitLdcInsn(targetHandle);
            case INVOKEDYNAMIC -> {
                Handle bootstrap = new Handle(
                        Opcodes.H_INVOKESTATIC,
                        "fixture/Bootstrap",
                        "bootstrapCallSite",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;"
                                + "Ljava/lang/String;"
                                + "Ljava/lang/invoke/MethodType;"
                                + "Ljava/lang/invoke/MethodHandle;)"
                                + "Ljava/lang/invoke/CallSite;",
                        false);
                visitor.visitInvokeDynamicInsn(
                        "observe",
                        "()Ljava/lang/Object;",
                        bootstrap,
                        targetHandle);
            }
            case CONSTANT_DYNAMIC -> {
                Handle bootstrap = new Handle(
                        Opcodes.H_INVOKESTATIC,
                        "fixture/Bootstrap",
                        "bootstrapConstant",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;"
                                + "Ljava/lang/String;"
                                + "Ljava/lang/Class;"
                                + "Ljava/lang/invoke/MethodHandle;)"
                                + "Ljava/lang/Object;",
                        false);
                visitor.visitLdcInsn(new ConstantDynamic(
                        "observed",
                        "Ljava/lang/Object;",
                        bootstrap,
                        targetHandle));
            }
        }
        visitor.visitInsn(Opcodes.POP);
        visitor.visitInsn(Opcodes.RETURN);
    }

    private ParsedProgram staticCrossOwnerProgram() {
        return program(
                type(
                        UTILITY,
                        "java/lang/Object",
                        ACC_PUBLIC,
                        method(
                                ACC_PROTECTED | ACC_STATIC,
                                "target",
                                "()I")),
                type(
                        CALLER,
                        "java/lang/Object",
                        ACC_PUBLIC,
                        method(
                                ACC_PUBLIC | ACC_STATIC,
                                "caller",
                                "()I")));
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
            NativeImplementationPath callerPath,
            List<String> callerDirectTargets,
            List<String> callerStaticTargets,
            List<String> callerDispatchTargets,
            boolean includeCallerImplementation) {
        NativeMethodImplementation target = implementation(
                NativeMethodInternalizationTestFixtures.method(
                        program,
                        targetMethodKey),
                NativeImplementationPath.LLVM_NATIVE_PATH,
                List.of(),
                List.of(),
                List.of());
        java.util.ArrayList<NativeMethodImplementation> implementations =
                new java.util.ArrayList<>();
        implementations.add(target);
        if (includeCallerImplementation) {
            implementations.add(implementation(
                    NativeMethodInternalizationTestFixtures.method(
                            program,
                            callerMethodKey),
                    callerPath,
                    callerDirectTargets,
                    callerStaticTargets,
                    callerDispatchTargets));
        }
        return new NativeMethodInternalizationPlanner().plan(
                true,
                WholeProgramAnalysisScope.DECLARED_CLOSED_WORLD,
                program,
                hierarchy(program),
                graph,
                reflectionPlan,
                Set.of(),
                new NativeImplementationPlan(implementations));
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

    private enum ReferenceKind {
        LDC_HANDLE,
        INVOKEDYNAMIC,
        CONSTANT_DYNAMIC
    }
}
