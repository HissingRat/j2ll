package xyz.melodysky.analysis.method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static xyz.melodysky.analysis.method.NativeMethodInternalizationTestFixtures.hierarchy;
import static xyz.melodysky.analysis.method.NativeMethodInternalizationTestFixtures.method;
import static xyz.melodysky.analysis.method.NativeMethodInternalizationTestFixtures.program;
import static xyz.melodysky.analysis.method.NativeMethodInternalizationTestFixtures.type;
import static xyz.melodysky.analysis.method.NativeMethodInternalizationTestFixtures.typeWithInterfaces;

import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.melodysky.frontend.classfile.ParsedProgram;

class KnownJvmCallbackObserverTest {
    private final KnownJvmCallbackObserver observer =
            new KnownJvmCallbackObserver();

    @Test
    void recognizesExactRunnableAndCallableContracts() {
        assertObservedInterfaceContract(
                "java/lang/Runnable",
                "run",
                "()V");
        assertObservedInterfaceContract(
                "java/util/concurrent/Callable",
                "call",
                "()Ljava/lang/Object;");
    }

    @Test
    void recognizesSupportedJdkLibraryCallbackContracts() {
        assertObservedInterfaceContract(
                "java/util/Comparator",
                "compare",
                "(Ljava/lang/Object;Ljava/lang/Object;)I");
        assertObservedInterfaceContract(
                "java/util/function/Function",
                "apply",
                "(Ljava/lang/Object;)Ljava/lang/Object;");
        assertObservedInterfaceContract(
                "java/util/function/Consumer",
                "accept",
                "(Ljava/lang/Object;)V");
        assertObservedInterfaceContract(
                "java/util/function/Supplier",
                "get",
                "()Ljava/lang/Object;");
        assertObservedInterfaceContract(
                "java/util/function/Predicate",
                "test",
                "(Ljava/lang/Object;)Z");
        assertObservedInterfaceContract(
                "java/util/function/BiFunction",
                "apply",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
        assertObservedInterfaceContract(
                "java/util/function/BiConsumer",
                "accept",
                "(Ljava/lang/Object;Ljava/lang/Object;)V");
        assertObservedInterfaceContract(
                "java/util/function/BiPredicate",
                "test",
                "(Ljava/lang/Object;Ljava/lang/Object;)Z");
        assertObservedInterfaceContract(
                "java/util/function/IntSupplier",
                "getAsInt",
                "()I");
        assertObservedInterfaceContract(
                "java/util/function/LongBinaryOperator",
                "applyAsLong",
                "(JJ)J");
        assertObservedSuperclassContract(
                "java/lang/Thread",
                "run",
                "()V");
        assertObservedSuperclassContract(
                "java/util/TimerTask",
                "run",
                "()V");
    }

    @Test
    void recognizesObjectVirtualEntriesWithoutBlanketOverrideRejection() {
        String owner = "fixture/ObjectOverrides";
        ParsedProgram program = program(type(
                owner,
                "java/lang/Object",
                ACC_PUBLIC,
                method(
                        ACC_PUBLIC,
                        "equals",
                        "(Ljava/lang/Object;)Z"),
                method(ACC_PUBLIC, "hashCode", "()I"),
                method(
                        ACC_PUBLIC,
                        "toString",
                        "()Ljava/lang/String;"),
                method(ACC_PUBLIC, "ordinary", "()I")));

        assertEquals(
                "java/lang/Object#equals!(Ljava/lang/Object;)Z",
                observer.observedContract(
                                method(
                                        program,
                                        owner + "#equals!(Ljava/lang/Object;)Z"),
                                hierarchy(program))
                        .orElseThrow());
        assertEquals(
                "java/lang/Object#hashCode!()I",
                observer.observedContract(
                                method(program, owner + "#hashCode!()I"),
                                hierarchy(program))
                        .orElseThrow());
        assertEquals(
                "java/lang/Object#toString!()Ljava/lang/String;",
                observer.observedContract(
                                method(
                                        program,
                                        owner + "#toString!()Ljava/lang/String;"),
                                hierarchy(program))
                        .orElseThrow());
        assertTrue(observer.observedContract(
                        method(program, owner + "#ordinary!()I"),
                        hierarchy(program))
                .isEmpty());
    }

    @Test
    void recognizesSerializablePrivateHook() {
        String owner = "fixture/SerializableValue";
        ParsedProgram program = program(typeWithInterfaces(
                owner,
                "java/lang/Object",
                ACC_PUBLIC,
                List.of("java/io/Serializable"),
                method(
                        org.objectweb.asm.Opcodes.ACC_PRIVATE,
                        "readObject",
                        "(Ljava/io/ObjectInputStream;)V")));

        assertEquals(
                "java/io/Serializable#readObject!(Ljava/io/ObjectInputStream;)V",
                observer.observedContract(
                                method(
                                        program,
                                        owner
                                                + "#readObject!(Ljava/io/ObjectInputStream;)V"),
                                hierarchy(program))
                        .orElseThrow());
    }

    @Test
    void followsApplicationHierarchyToInheritedContract() {
        String parent = "fixture/CallbackParent";
        String owner = "fixture/CallbackChild";
        ParsedProgram program = program(
                typeWithInterfaces(
                        parent,
                        "java/lang/Object",
                        ACC_PUBLIC,
                        List.of("java/lang/Runnable")),
                type(
                        owner,
                        parent,
                        ACC_PUBLIC,
                        method(ACC_PUBLIC, "run", "()V")));

        assertEquals(
                "java/lang/Runnable#run!()V",
                observer.observedContract(
                                method(program, owner + "#run!()V"),
                                hierarchy(program))
                        .orElseThrow());
    }

    @Test
    void doesNotTreatNameMatchOrOrdinaryOverrideAsCallback() {
        String owner = "fixture/UnrelatedRunner";
        ParsedProgram program = program(type(
                owner,
                "java/lang/Object",
                ACC_PUBLIC,
                method(ACC_PUBLIC, "run", "()V")));

        assertTrue(observer.observedContract(
                        method(program, owner + "#run!()V"),
                        hierarchy(program))
                .isEmpty());
    }

    private void assertObservedInterfaceContract(
            String callbackOwner,
            String name,
            String descriptor) {
        String owner = "fixture/InterfaceCallback" + name;
        ParsedProgram program = program(typeWithInterfaces(
                owner,
                "java/lang/Object",
                ACC_PUBLIC,
                List.of(callbackOwner),
                method(ACC_PUBLIC, name, descriptor)));
        assertEquals(
                callbackOwner + "#" + name + "!" + descriptor,
                observer.observedContract(
                                method(
                                        program,
                                        owner + "#" + name + "!" + descriptor),
                                hierarchy(program))
                        .orElseThrow());
    }

    private void assertObservedSuperclassContract(
            String callbackOwner,
            String name,
            String descriptor) {
        String owner = "fixture/ClassCallback" + name
                + callbackOwner.substring(callbackOwner.lastIndexOf('/') + 1);
        ParsedProgram program = program(type(
                owner,
                callbackOwner,
                ACC_PUBLIC,
                method(ACC_PUBLIC, name, descriptor)));
        assertEquals(
                callbackOwner + "#" + name + "!" + descriptor,
                observer.observedContract(
                                method(
                                        program,
                                        owner + "#" + name + "!" + descriptor),
                                hierarchy(program))
                        .orElseThrow());
    }
}
