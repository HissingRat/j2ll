package xyz.melodysky.analysis.method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static xyz.melodysky.analysis.method.NativeMethodInternalizationTestFixtures.callGraph;
import static xyz.melodysky.analysis.method.NativeMethodInternalizationTestFixtures.hierarchy;
import static xyz.melodysky.analysis.method.NativeMethodInternalizationTestFixtures.implementation;
import static xyz.melodysky.analysis.method.NativeMethodInternalizationTestFixtures.known;
import static xyz.melodysky.analysis.method.NativeMethodInternalizationTestFixtures.method;
import static xyz.melodysky.analysis.method.NativeMethodInternalizationTestFixtures.noReflection;
import static xyz.melodysky.analysis.method.NativeMethodInternalizationTestFixtures.program;
import static xyz.melodysky.analysis.method.NativeMethodInternalizationTestFixtures.type;
import static xyz.melodysky.analysis.method.NativeMethodInternalizationTestFixtures.typeWithInterfaces;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import xyz.melodysky.analysis.callgraph.InvokeKind;
import xyz.melodysky.analysis.world.WholeProgramAnalysisScope;
import xyz.melodysky.frontend.classfile.ParsedProgram;
import xyz.melodysky.toolchain.NativeImplementationPath;
import xyz.melodysky.toolchain.NativeImplementationPlan;

class KnownJvmCallbackInternalizationTest {
    @Test
    void keepsAllowlistedRunnableAndCallableEntries() {
        assertCallbackKept(
                "fixture/RunnableWorker",
                "java/lang/Runnable",
                "run",
                "()V");
        assertCallbackKept(
                "fixture/CallableWorker",
                "java/util/concurrent/Callable",
                "call",
                "()Ljava/lang/Object;");
    }

    @Test
    void unrelatedRunMethodCanStillBeInternalized() {
        String owner = "fixture/UnrelatedRunner";
        String targetKey = owner + "#run!()V";
        String callerKey = owner + "#caller!()V";
        ParsedProgram program = program(type(
                owner,
                "java/lang/Object",
                ACC_PUBLIC,
                method(ACC_PUBLIC, "run", "()V"),
                method(ACC_PUBLIC, "caller", "()V")));

        NativeMethodInternalizationDecision decision = decision(
                program,
                targetKey,
                callerKey);

        assertTrue(decision.internalized(), () -> decision.reasons().toString());
    }

    @Test
    void keepsObjectVirtualEntry() {
        String owner = "fixture/Value";
        String targetKey = owner + "#equals!(Ljava/lang/Object;)Z";
        String callerKey = owner + "#caller!(Ljava/lang/Object;)Z";
        ParsedProgram program = program(type(
                owner,
                "java/lang/Object",
                ACC_PUBLIC,
                method(
                        ACC_PUBLIC,
                        "equals",
                        "(Ljava/lang/Object;)Z"),
                method(
                        ACC_PUBLIC,
                        "caller",
                        "(Ljava/lang/Object;)Z")));

        assertKnownCallbackReason(decision(
                program,
                targetKey,
                callerKey));
    }

    @Test
    void keepsPrivateSerializableHook() {
        String owner = "fixture/SerializableValue";
        String descriptor = "(Ljava/io/ObjectInputStream;)V";
        String targetKey = owner + "#readObject!" + descriptor;
        String callerKey = owner + "#caller!" + descriptor;
        ParsedProgram program = program(typeWithInterfaces(
                owner,
                "java/lang/Object",
                ACC_PUBLIC,
                List.of("java/io/Serializable"),
                method(ACC_PRIVATE, "readObject", descriptor),
                method(ACC_PUBLIC, "caller", descriptor)));

        assertKnownCallbackReason(decision(
                program,
                targetKey,
                callerKey,
                InvokeKind.SPECIAL));
    }

    private void assertCallbackKept(
            String owner,
            String callbackOwner,
            String name,
            String descriptor) {
        String targetKey = owner + "#" + name + "!" + descriptor;
        String callerKey = owner + "#caller!" + descriptor;
        ParsedProgram program = program(typeWithInterfaces(
                owner,
                "java/lang/Object",
                ACC_PUBLIC,
                List.of(callbackOwner),
                method(ACC_PUBLIC, name, descriptor),
                method(ACC_PUBLIC, "caller", descriptor)));

        NativeMethodInternalizationDecision decision = decision(
                program,
                targetKey,
                callerKey);

        assertKnownCallbackReason(decision);
    }

    private void assertKnownCallbackReason(
            NativeMethodInternalizationDecision decision) {
        assertFalse(decision.internalized());
        assertTrue(
                decision.reasons().contains(
                        NativeMethodInternalizationReason
                                .METHOD_INTERNALIZATION_KNOWN_JVM_CALLBACK_ENTRY),
                () -> decision.reasons().toString());
    }

    private NativeMethodInternalizationDecision decision(
            ParsedProgram program,
            String targetKey,
            String callerKey) {
        return decision(
                program,
                targetKey,
                callerKey,
                InvokeKind.VIRTUAL);
    }

    private NativeMethodInternalizationDecision decision(
            ParsedProgram program,
            String targetKey,
            String callerKey,
            InvokeKind invokeKind) {
        boolean direct = invokeKind == InvokeKind.SPECIAL;
        NativeImplementationPlan implementations = new NativeImplementationPlan(
                List.of(
                        implementation(
                                method(program, targetKey),
                                NativeImplementationPath.LLVM_NATIVE_PATH,
                                List.of(),
                                List.of(),
                                List.of()),
                        implementation(
                                method(program, callerKey),
                                NativeImplementationPath.LLVM_NATIVE_PATH,
                                direct ? List.of(targetKey) : List.of(),
                                List.of(),
                                direct ? List.of() : List.of(targetKey))));
        return new NativeMethodInternalizationPlanner()
                .plan(
                        true,
                        WholeProgramAnalysisScope.DECLARED_CLOSED_WORLD,
                        program,
                        hierarchy(program),
                        callGraph(
                                callerKey,
                                invokeKind,
                                targetKey,
                                List.of(known(targetKey))),
                        noReflection(),
                        Set.of(),
                        implementations,
                        Set.of(NativeMethodId.fromMethodKey(targetKey)))
                .decisionFor(targetKey)
                .orElseThrow();
    }
}
