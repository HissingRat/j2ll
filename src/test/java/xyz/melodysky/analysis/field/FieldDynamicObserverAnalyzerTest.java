package xyz.melodysky.analysis.field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.SourceValue;
import xyz.melodysky.frontend.classfile.ParsedClass;
import xyz.melodysky.frontend.classfile.ParsedField;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.frontend.classfile.ParsedProgram;
import xyz.melodysky.jvm.AccessFlags;

class FieldDynamicObserverAnalyzerTest implements Opcodes {
    private static final String CALLER = "sample/Caller";
    private static final String TARGET = "sample/Target";
    private static final String EXTERNAL = "external/State";

    @Test
    void sourceMergeCapUsesCanonicalAbsorbingUnknownValues() {
        FieldObserverSourceInterpreter interpreter = new FieldObserverSourceInterpreter();
        SourceValue empty = new SourceValue(1);
        assertSame(empty, interpreter.merge(empty, new SourceValue(1)));

        SourceValue merged = new SourceValue(1, new InsnNode(ICONST_0));
        SourceValue unknown = null;
        for (int index = 0; index < 64; index++) {
            merged = interpreter.merge(
                    merged,
                    new SourceValue(1, new InsnNode(ICONST_1)));
            if (merged.insns.stream().anyMatch(interpreter::isUnknownSource)) {
                unknown = merged;
                break;
            }
        }

        assertNotNull(unknown);
        SourceValue known = new SourceValue(1, new InsnNode(ICONST_2));
        assertSame(unknown, interpreter.merge(unknown, known));
        assertSame(unknown, interpreter.merge(known, unknown));
        assertSame(unknown, interpreter.merge(new SourceValue(1), known));
        assertSame(unknown, interpreter.merge(unknown, unknown));
    }

    @Test
    void deepCopyProvenanceFailsClosedWithoutRecursiveOverflow() {
        ArrayList<AbstractInsnNode> instructions = new ArrayList<>();
        instructions.add(new LdcInsnNode(Type.getObjectType(TARGET)));
        instructions.add(new VarInsnNode(ASTORE, 0));
        for (int local = 1; local <= 256; local++) {
            instructions.add(new VarInsnNode(ALOAD, local - 1));
            instructions.add(new VarInsnNode(ASTORE, local));
        }
        instructions.add(new VarInsnNode(ALOAD, 256));
        instructions.add(new LdcInsnNode("secret"));
        instructions.add(virtual(
                "java/lang/Class",
                "getDeclaredField",
                "(Ljava/lang/String;)Ljava/lang/reflect/Field;"));
        instructions.add(new InsnNode(POP));
        instructions.add(new InsnNode(RETURN));
        ParsedMethod method = method(
                CALLER,
                "deepCopy",
                "()V",
                instructions.toArray(AbstractInsnNode[]::new));

        FieldDynamicObservationPlan plan = assertTimeoutPreemptively(
                Duration.ofSeconds(5),
                () -> analyze(
                        program(clazz(CALLER, List.of(), List.of(method)), target(TARGET, "secret")),
                        List.of()));

        assertEquals(1, plan.observations().size());
        assertEquals(FieldObservationScope.GLOBAL, plan.observations().getFirst().scope());
    }

    @Test
    void highMergeObserverCfgConvergesToStableGlobalObservation() {
        int alternatives = 4096;
        LabelNode fallback = new LabelNode();
        LabelNode joined = new LabelNode();
        LabelNode[] labels = new LabelNode[alternatives];
        int[] keys = new int[alternatives];
        for (int index = 0; index < alternatives; index++) {
            labels[index] = new LabelNode();
            keys[index] = index;
        }

        ArrayList<AbstractInsnNode> instructions = new ArrayList<>();
        instructions.add(new VarInsnNode(ILOAD, 0));
        instructions.add(new LookupSwitchInsnNode(fallback, keys, labels));
        for (LabelNode label : labels) {
            instructions.add(label);
            instructions.add(new LdcInsnNode(Type.getObjectType(TARGET)));
            instructions.add(new VarInsnNode(ASTORE, 1));
            instructions.add(new JumpInsnNode(GOTO, joined));
        }
        instructions.add(fallback);
        instructions.add(new LdcInsnNode(Type.getObjectType(TARGET)));
        instructions.add(new VarInsnNode(ASTORE, 1));
        instructions.add(joined);
        instructions.add(new VarInsnNode(ALOAD, 1));
        instructions.add(new LdcInsnNode("secret"));
        instructions.add(virtual(
                "java/lang/Class",
                "getDeclaredField",
                "(Ljava/lang/String;)Ljava/lang/reflect/Field;"));
        instructions.add(new InsnNode(POP));
        instructions.add(new InsnNode(RETURN));
        ParsedMethod method = method(
                CALLER,
                "highMerge",
                "(I)V",
                instructions.toArray(AbstractInsnNode[]::new));

        FieldDynamicObservationPlan plan = assertTimeoutPreemptively(
                Duration.ofSeconds(5),
                () -> analyze(
                        program(clazz(CALLER, List.of(), List.of(method)), target(TARGET, "secret")),
                        List.of()));

        assertEquals(1, plan.observations().size());
        FieldDynamicObservation observation = plan.observations().getFirst();
        assertEquals(FieldObservationScope.GLOBAL, observation.scope());
        assertEquals(FieldDynamicBoundaryKind.REFLECTION, observation.observerKind());
    }

    @Test
    void diamondCopyDagUsesOneSharedResolutionBudget() {
        int levels = 24;
        ArrayList<AbstractInsnNode> instructions = new ArrayList<>();
        instructions.add(new LdcInsnNode(Type.getObjectType(TARGET)));
        instructions.add(new VarInsnNode(ASTORE, 1));
        for (int level = 0; level < levels; level++) {
            int sourceLocal = level + 1;
            int targetLocal = level + 2;
            LabelNode second = new LabelNode();
            LabelNode joined = new LabelNode();
            instructions.add(new VarInsnNode(ILOAD, 0));
            instructions.add(new JumpInsnNode(IFEQ, second));
            instructions.add(new VarInsnNode(ALOAD, sourceLocal));
            instructions.add(new VarInsnNode(ASTORE, targetLocal));
            instructions.add(new JumpInsnNode(GOTO, joined));
            instructions.add(second);
            instructions.add(new VarInsnNode(ALOAD, sourceLocal));
            instructions.add(new VarInsnNode(ASTORE, targetLocal));
            instructions.add(joined);
        }
        instructions.add(new VarInsnNode(ALOAD, levels + 1));
        instructions.add(new LdcInsnNode("secret"));
        instructions.add(virtual(
                "java/lang/Class",
                "getDeclaredField",
                "(Ljava/lang/String;)Ljava/lang/reflect/Field;"));
        instructions.add(new InsnNode(POP));
        instructions.add(new InsnNode(RETURN));
        ParsedMethod method = method(
                CALLER,
                "diamondCopies",
                "(Z)V",
                instructions.toArray(AbstractInsnNode[]::new));

        FieldDynamicObservationPlan plan = assertTimeoutPreemptively(
                Duration.ofSeconds(5),
                () -> analyze(
                        program(clazz(CALLER, List.of(), List.of(method)), target(TARGET, "secret")),
                        List.of()));

        assertEquals(1, plan.observations().size());
        FieldDynamicObservation observation = plan.observations().getFirst();
        assertEquals(FieldObservationScope.GLOBAL, observation.scope());
        assertEquals(FieldDynamicBoundaryKind.REFLECTION, observation.observerKind());
    }

    @Test
    void branchJoinOfSameClassOwnerStaysExact() {
        LabelNode second = new LabelNode();
        LabelNode joined = new LabelNode();
        ParsedMethod method = method(
                CALLER,
                "joined",
                "(Z)V",
                new VarInsnNode(ILOAD, 0),
                new JumpInsnNode(IFEQ, second),
                new LdcInsnNode(Type.getObjectType(TARGET)),
                new VarInsnNode(ASTORE, 1),
                new JumpInsnNode(GOTO, joined),
                second,
                new LdcInsnNode(Type.getObjectType(TARGET)),
                new VarInsnNode(ASTORE, 1),
                joined,
                new VarInsnNode(ALOAD, 1),
                new LdcInsnNode("secret"),
                virtual("java/lang/Class", "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;"),
                new InsnNode(POP),
                new InsnNode(RETURN));

        FieldDynamicObservationPlan plan = analyze(
                program(clazz(CALLER, List.of(), List.of(method)), target(TARGET, "secret")),
                List.of());

        assertFalse(plan.hasGlobalObservation());
        assertEquals(
                SetView.of(new FieldId(TARGET, "secret", "I")),
                SetView.of(plan.observations().stream()
                        .flatMap(observation -> observation.exactField().stream())
                        .toList()));
    }

    @Test
    void mixedKnownAndParameterClassOrNameCannotBecomeFalseExact() {
        LabelNode ownerParameter = new LabelNode();
        LabelNode ownerJoin = new LabelNode();
        ParsedMethod mixedOwner = method(
                CALLER,
                "mixedOwner",
                "(ZLjava/lang/Class;)V",
                new VarInsnNode(ILOAD, 0),
                new JumpInsnNode(IFEQ, ownerParameter),
                new LdcInsnNode(Type.getObjectType(TARGET)),
                new VarInsnNode(ASTORE, 2),
                new JumpInsnNode(GOTO, ownerJoin),
                ownerParameter,
                new VarInsnNode(ALOAD, 1),
                new VarInsnNode(ASTORE, 2),
                ownerJoin,
                new VarInsnNode(ALOAD, 2),
                virtual("java/lang/Class", "getDeclaredFields", "()[Ljava/lang/reflect/Field;"),
                new InsnNode(POP),
                new InsnNode(RETURN));

        LabelNode nameParameter = new LabelNode();
        LabelNode nameJoin = new LabelNode();
        ParsedMethod mixedName = method(
                CALLER,
                "mixedName",
                "(ZLjava/lang/String;)V",
                new VarInsnNode(ILOAD, 0),
                new JumpInsnNode(IFEQ, nameParameter),
                new LdcInsnNode("secret"),
                new VarInsnNode(ASTORE, 2),
                new JumpInsnNode(GOTO, nameJoin),
                nameParameter,
                new VarInsnNode(ALOAD, 1),
                new VarInsnNode(ASTORE, 2),
                nameJoin,
                new LdcInsnNode(Type.getObjectType(TARGET)),
                new VarInsnNode(ALOAD, 2),
                virtual("java/lang/Class", "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;"),
                new InsnNode(POP),
                new InsnNode(RETURN));

        ParsedMethod directParameter = method(
                CALLER,
                "parameterOwner",
                "(Ljava/lang/Class;)V",
                new VarInsnNode(ALOAD, 0),
                virtual("java/lang/Class", "getDeclaredFields", "()[Ljava/lang/reflect/Field;"),
                new InsnNode(POP),
                new InsnNode(RETURN));

        FieldDynamicObservationPlan plan = analyze(
                program(clazz(
                        CALLER,
                        List.of(),
                        List.of(mixedOwner, mixedName, directParameter)), target(TARGET, "secret")),
                List.of());

        assertTrue(plan.observations().stream().anyMatch(observation ->
                observation.methodKey().contains("#mixedOwner!")
                        && observation.scope() == FieldObservationScope.GLOBAL));
        assertTrue(plan.observations().stream().anyMatch(observation ->
                observation.methodKey().contains("#parameterOwner!")
                        && observation.scope() == FieldObservationScope.GLOBAL));
        assertTrue(plan.observations().stream().anyMatch(observation ->
                observation.methodKey().contains("#mixedName!")
                        && observation.scope() == FieldObservationScope.OWNER
                        && observation.owner().orElseThrow().equals(TARGET)));
        assertFalse(plan.observations().stream().anyMatch(observation ->
                observation.methodKey().contains("#mixedName!")
                        && observation.scope() == FieldObservationScope.EXACT));
    }

    @Test
    void staleMaxStackAndDynamicGetStaticReceiverFailClosedToGlobal() {
        ParsedMethod method = method(
                CALLER,
                "dynamic",
                "()V",
                new FieldInsnNode(GETSTATIC, CALLER, "dynamicClass", "Ljava/lang/Class;"),
                new LdcInsnNode("secret"),
                virtual("java/lang/Class", "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;"),
                new InsnNode(POP),
                new InsnNode(RETURN));
        method.methodNode().maxStack = 0;

        FieldDynamicObservationPlan plan = analyze(
                program(clazz(
                        CALLER,
                        List.of(field(CALLER, "dynamicClass", "Ljava/lang/Class;")),
                        List.of(method)), target(TARGET, "secret")),
                List.of());

        assertTrue(plan.hasGlobalObservation());
    }

    @Test
    void analyzerFailureAtObserverMethodFailsClosedToGlobal() {
        ParsedMethod malformed = method(
                CALLER,
                "malformed",
                "()V",
                new InsnNode(POP),
                virtual("java/lang/Class", "getDeclaredFields", "()[Ljava/lang/reflect/Field;"),
                new InsnNode(POP),
                new InsnNode(RETURN));

        FieldDynamicObservationPlan plan = analyze(
                program(clazz(CALLER, List.of(), List.of(malformed))),
                List.of());

        assertTrue(plan.observations().stream().anyMatch(observation ->
                observation.scope() == FieldObservationScope.GLOBAL
                        && observation.observerKind() == FieldDynamicBoundaryKind.REFLECTION));
    }

    @Test
    void lookupFindStaticGetterProducesCrossOwnerExactMethodHandleFact() {
        ParsedMethod method = method(
                CALLER,
                "lookup",
                "()V",
                staticCall("java/lang/invoke/MethodHandles", "lookup", "()Ljava/lang/invoke/MethodHandles$Lookup;"),
                new LdcInsnNode(Type.getObjectType(TARGET)),
                new LdcInsnNode("secret"),
                new FieldInsnNode(GETSTATIC, "java/lang/Integer", "TYPE", "Ljava/lang/Class;"),
                virtual(
                        "java/lang/invoke/MethodHandles$Lookup",
                        "findStaticGetter",
                        "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;"),
                new InsnNode(POP),
                new InsnNode(RETURN));

        FieldDynamicObservationPlan plan = analyze(
                program(clazz(CALLER, List.of(), List.of(method)), target(TARGET, "secret")),
                List.of());

        assertFalse(plan.hasGlobalObservation());
        assertTrue(plan.observerKindsFor(new FieldId(TARGET, "secret", "I"))
                .contains(FieldDynamicBoundaryKind.METHOD_HANDLE));
    }

    @Test
    void lookupVarHandleAndUnreflectGetterPreserveExactTargetThroughLocals() {
        ParsedMethod varHandle = method(
                CALLER,
                "varHandle",
                "()V",
                staticCall("java/lang/invoke/MethodHandles", "lookup", "()Ljava/lang/invoke/MethodHandles$Lookup;"),
                new LdcInsnNode(Type.getObjectType(TARGET)),
                new LdcInsnNode("secret"),
                new FieldInsnNode(GETSTATIC, "java/lang/Integer", "TYPE", "Ljava/lang/Class;"),
                virtual(
                        "java/lang/invoke/MethodHandles$Lookup",
                        "findStaticVarHandle",
                        "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/VarHandle;"),
                new VarInsnNode(ASTORE, 0),
                new VarInsnNode(ALOAD, 0),
                virtual("java/lang/invoke/VarHandle", "get", "()I"),
                new InsnNode(POP),
                new InsnNode(RETURN));
        ParsedMethod unreflect = method(
                CALLER,
                "unreflect",
                "()V",
                new LdcInsnNode(Type.getObjectType(TARGET)),
                new LdcInsnNode("secret"),
                virtual("java/lang/Class", "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;"),
                new VarInsnNode(ASTORE, 0),
                staticCall("java/lang/invoke/MethodHandles", "lookup", "()Ljava/lang/invoke/MethodHandles$Lookup;"),
                new VarInsnNode(ALOAD, 0),
                virtual(
                        "java/lang/invoke/MethodHandles$Lookup",
                        "unreflectGetter",
                        "(Ljava/lang/reflect/Field;)Ljava/lang/invoke/MethodHandle;"),
                new InsnNode(POP),
                new InsnNode(RETURN));
        FieldId target = new FieldId(TARGET, "secret", "I");

        FieldDynamicObservationPlan plan = analyze(
                program(clazz(CALLER, List.of(), List.of(varHandle, unreflect)), target(TARGET, "secret")),
                List.of());

        assertFalse(plan.hasGlobalObservation(), plan.observations().toString());
        assertTrue(plan.observerKindsFor(target).contains(FieldDynamicBoundaryKind.VAR_HANDLE));
        assertTrue(plan.observerKindsFor(target).contains(FieldDynamicBoundaryKind.METHOD_HANDLE));
    }

    @Test
    void knownExternalFieldUnsafeBaseOffsetChainDoesNotPolluteInputOwner() {
        ParsedMethod method = unsafeExternalChain();
        ParsedProgram input = program(
                clazz(CALLER, List.of(field(CALLER, "local", "I")), List.of(method)));
        ParsedProgram classpath = program(target(EXTERNAL, "remote"));

        FieldUseIndex index = new FieldUseAnalyzer().analyze(input, List.of(classpath));

        assertFalse(index.dynamicObservationPlan().hasGlobalObservation());
        assertTrue(index.dynamicObserverKindsFor(new FieldId(CALLER, "local", "I")).isEmpty());
        assertTrue(index.dynamicObserverKindsFor(new FieldId(EXTERNAL, "remote", "I"))
                .contains(FieldDynamicBoundaryKind.UNSAFE));
    }

    @Test
    void knownButUnparsedExternalOwnerRemainsOwnerScopedThroughFieldArrayLoad() {
        ParsedMethod lookup = method(
                CALLER,
                "externalLookup",
                "()V",
                new LdcInsnNode(Type.getObjectType(EXTERNAL)),
                new LdcInsnNode("remote"),
                virtual("java/lang/Class", "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;"),
                new VarInsnNode(ASTORE, 0),
                new VarInsnNode(ALOAD, 0),
                new InsnNode(ACONST_NULL),
                virtual("java/lang/reflect/Field", "get", "(Ljava/lang/Object;)Ljava/lang/Object;"),
                new InsnNode(POP),
                new InsnNode(RETURN));
        ParsedMethod scan = method(
                CALLER,
                "externalScan",
                "()V",
                new LdcInsnNode(Type.getObjectType(EXTERNAL)),
                virtual("java/lang/Class", "getDeclaredFields", "()[Ljava/lang/reflect/Field;"),
                new InsnNode(ICONST_0),
                new InsnNode(AALOAD),
                new InsnNode(ACONST_NULL),
                virtual("java/lang/reflect/Field", "get", "(Ljava/lang/Object;)Ljava/lang/Object;"),
                new InsnNode(POP),
                new InsnNode(RETURN));

        FieldDynamicObservationPlan plan = analyze(
                program(clazz(CALLER, List.of(), List.of(lookup, scan))),
                List.of());

        assertFalse(plan.hasGlobalObservation(), plan.observations().toString());
        assertTrue(plan.observations().stream().allMatch(observation ->
                observation.owner().orElse(EXTERNAL).equals(EXTERNAL)));
    }

    @Test
    void unknownVarHandleAndUnsafeCoordinatesAreGlobal() {
        ParsedMethod varHandle = method(
                CALLER,
                "unknownVarHandle",
                "(Ljava/lang/invoke/VarHandle;)V",
                new VarInsnNode(ALOAD, 0),
                virtual("java/lang/invoke/VarHandle", "get", "()Ljava/lang/Object;"),
                new InsnNode(POP),
                new InsnNode(RETURN));
        ParsedMethod unsafe = method(
                CALLER,
                "unknownUnsafe",
                "(Ljava/lang/Object;J)V",
                new FieldInsnNode(GETSTATIC, "sun/misc/Unsafe", "theUnsafe", "Lsun/misc/Unsafe;"),
                new VarInsnNode(ALOAD, 0),
                new VarInsnNode(LLOAD, 1),
                virtual("sun/misc/Unsafe", "getInt", "(Ljava/lang/Object;J)I"),
                new InsnNode(POP),
                new InsnNode(RETURN));

        FieldDynamicObservationPlan plan = analyze(
                program(clazz(CALLER, List.of(), List.of(varHandle, unsafe))),
                List.of());

        assertTrue(plan.observations().stream().anyMatch(observation ->
                observation.scope() == FieldObservationScope.GLOBAL
                        && observation.observerKind() == FieldDynamicBoundaryKind.VAR_HANDLE));
        assertTrue(plan.observations().stream().anyMatch(observation ->
                observation.scope() == FieldObservationScope.GLOBAL
                        && observation.observerKind() == FieldDynamicBoundaryKind.UNSAFE));
    }

    @Test
    void mixedExactAndParameterFieldHandlesRemainGlobal() {
        ParsedMethod reflection = mixedReflectionField();
        ParsedMethod methodHandle = mixedMethodHandle();
        ParsedMethod varHandle = mixedVarHandle();
        ParsedMethod unsafe = mixedUnsafeOffset();

        FieldDynamicObservationPlan plan = analyze(
                program(clazz(
                        CALLER,
                        List.of(),
                        List.of(reflection, methodHandle, varHandle, unsafe)), target(TARGET, "secret")),
                List.of());

        for (FieldDynamicBoundaryKind kind : List.of(
                FieldDynamicBoundaryKind.REFLECTION,
                FieldDynamicBoundaryKind.METHOD_HANDLE,
                FieldDynamicBoundaryKind.VAR_HANDLE,
                FieldDynamicBoundaryKind.UNSAFE)) {
            assertTrue(plan.observations().stream().anyMatch(observation ->
                    observation.scope() == FieldObservationScope.GLOBAL
                            && observation.observerKind() == kind),
                    () -> "missing mixed-source GLOBAL for " + kind + ": " + plan.observations());
        }
    }

    @Test
    void exactLdcFieldHandleUsesExistingAccessSiteWithoutGlobalObservation() {
        Handle handle = new Handle(H_GETSTATIC, TARGET, "secret", "I", false);
        ParsedMethod method = method(
                CALLER,
                "constantHandle",
                "()V",
                new LdcInsnNode(handle),
                new InsnNode(POP),
                new InsnNode(RETURN));
        ParsedProgram input = program(
                clazz(CALLER, List.of(), List.of(method)),
                target(TARGET, "secret"));

        FieldUseIndex index = new FieldUseAnalyzer().analyze(input);

        assertFalse(index.dynamicObservationPlan().hasGlobalObservation());
        assertTrue(index.dynamicObservationPlan().observations().isEmpty());
        assertEquals(
                FieldReferenceKind.METHOD_HANDLE_STATIC_READ,
                index.accessesFor(new FieldId(TARGET, "secret", "I"))
                        .get(0)
                        .referenceKind());
    }

    @Test
    void knownOrdinaryMethodHandleInvokeDoesNotCreateFieldObservation() {
        ParsedMethod method = method(
                CALLER,
                "ordinaryHandle",
                "()V",
                staticCall("java/lang/invoke/MethodHandles", "lookup", "()Ljava/lang/invoke/MethodHandles$Lookup;"),
                new LdcInsnNode(Type.getObjectType(TARGET)),
                new LdcInsnNode("ordinary"),
                new LdcInsnNode(Type.getMethodType("()V")),
                virtual(
                        "java/lang/invoke/MethodHandles$Lookup",
                        "findStatic",
                        "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"),
                virtual("java/lang/invoke/MethodHandle", "invokeExact", "()V"),
                new InsnNode(RETURN));

        FieldDynamicObservationPlan plan = analyze(
                program(
                        clazz(CALLER, List.of(), List.of(method)),
                        clazz(
                                TARGET,
                                List.of(field(TARGET, "secret", "I")),
                                List.of(method(
                                        TARGET,
                                        "ordinary",
                                        "()V",
                                        new InsnNode(RETURN))))),
                List.of());

        assertTrue(plan.observations().isEmpty());
    }

    @Test
    void observerApisReachedThroughOrdinaryLookupHandlesFailClosed() {
        ParsedMethod reflection = method(
                CALLER,
                "indirectReflection",
                "()V",
                staticCall("java/lang/invoke/MethodHandles", "lookup", "()Ljava/lang/invoke/MethodHandles$Lookup;"),
                new LdcInsnNode(Type.getType(Class.class)),
                new LdcInsnNode("getDeclaredField"),
                new LdcInsnNode(Type.getMethodType(
                        Type.getType(java.lang.reflect.Field.class),
                        Type.getType(String.class))),
                virtual(
                        "java/lang/invoke/MethodHandles$Lookup",
                        "findVirtual",
                        "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"),
                new LdcInsnNode(Type.getObjectType(TARGET)),
                new LdcInsnNode("secret"),
                virtual(
                        "java/lang/invoke/MethodHandle",
                        "invokeExact",
                        "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/reflect/Field;"),
                new InsnNode(POP),
                new InsnNode(RETURN));
        ParsedMethod nativeLoad = method(
                CALLER,
                "indirectLoad",
                "()V",
                staticCall("java/lang/invoke/MethodHandles", "lookup", "()Ljava/lang/invoke/MethodHandles$Lookup;"),
                new LdcInsnNode(Type.getType(System.class)),
                new LdcInsnNode("loadLibrary"),
                new LdcInsnNode(Type.getMethodType(
                        Type.VOID_TYPE,
                        Type.getType(String.class))),
                virtual(
                        "java/lang/invoke/MethodHandles$Lookup",
                        "findStatic",
                        "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"),
                new LdcInsnNode("sample"),
                virtual(
                        "java/lang/invoke/MethodHandle",
                        "invokeExact",
                        "(Ljava/lang/String;)V"),
                new InsnNode(RETURN));

        FieldDynamicObservationPlan plan = analyze(
                program(clazz(CALLER, List.of(), List.of(reflection, nativeLoad))),
                List.of());

        assertTrue(plan.observations().stream().anyMatch(observation ->
                observation.scope() == FieldObservationScope.GLOBAL
                        && observation.observerKind() == FieldDynamicBoundaryKind.METHOD_HANDLE));
    }

    @Test
    void unresolvedReflectMethodAndIndyObserverHandleFailClosed() {
        ParsedMethod methodInvoke = method(
                CALLER,
                "methodInvoke",
                "(Ljava/lang/reflect/Method;)V",
                new VarInsnNode(ALOAD, 0),
                new InsnNode(ACONST_NULL),
                new InsnNode(ACONST_NULL),
                virtual(
                        "java/lang/reflect/Method",
                        "invoke",
                        "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;"),
                new InsnNode(POP),
                new InsnNode(RETURN));
        Handle bootstrap = new Handle(
                H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory",
                "metafactory",
                "()Ljava/lang/invoke/CallSite;",
                false);
        Handle observer = new Handle(
                H_INVOKEVIRTUAL,
                "java/lang/Class",
                "getDeclaredFields",
                "()[Ljava/lang/reflect/Field;",
                false);
        ParsedMethod indy = method(
                CALLER,
                "indyObserver",
                "()V",
                new InvokeDynamicInsnNode(
                        "run",
                        "()Ljava/lang/Runnable;",
                        bootstrap,
                        observer),
                new InsnNode(POP),
                new InsnNode(RETURN));
        ParsedProgram input = program(clazz(
                CALLER,
                List.of(),
                List.of(methodInvoke, indy)));

        FieldUseIndex index = new FieldUseAnalyzer().analyze(input);

        assertTrue(index.dynamicObservationPlan().observations().stream().anyMatch(observation ->
                observation.scope() == FieldObservationScope.GLOBAL
                        && observation.observerKind() == FieldDynamicBoundaryKind.REFLECTION));
    }

    @Test
    void unknownMethodHandleInvokeWithArgumentsFailsClosedToGlobal() {
        ParsedMethod method = method(
                CALLER,
                "unknownHandle",
                "(Ljava/lang/invoke/MethodHandle;Ljava/util/List;)V",
                new VarInsnNode(ALOAD, 0),
                new VarInsnNode(ALOAD, 1),
                virtual(
                        "java/lang/invoke/MethodHandle",
                        "invokeWithArguments",
                        "(Ljava/util/List;)Ljava/lang/Object;"),
                new InsnNode(POP),
                new InsnNode(RETURN));

        FieldDynamicObservationPlan plan = analyze(
                program(clazz(CALLER, List.of(), List.of(method))),
                List.of());

        assertTrue(plan.observations().stream().anyMatch(observation ->
                observation.scope() == FieldObservationScope.GLOBAL
                        && observation.observerKind() == FieldDynamicBoundaryKind.METHOD_HANDLE));
    }

    @Test
    void staticFieldBaseOnlyNarrowsToOwnerAndUnknownOffsetRemainsGlobal() {
        ParsedMethod method = method(
                CALLER,
                "unsafeUnknownOffset",
                "(J)V",
                new LdcInsnNode(Type.getObjectType(TARGET)),
                new LdcInsnNode("first"),
                virtual("java/lang/Class", "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;"),
                new VarInsnNode(ASTORE, 2),
                new FieldInsnNode(GETSTATIC, "sun/misc/Unsafe", "theUnsafe", "Lsun/misc/Unsafe;"),
                new VarInsnNode(ALOAD, 2),
                virtual("sun/misc/Unsafe", "staticFieldBase", "(Ljava/lang/reflect/Field;)Ljava/lang/Object;"),
                new VarInsnNode(ASTORE, 3),
                new FieldInsnNode(GETSTATIC, "sun/misc/Unsafe", "theUnsafe", "Lsun/misc/Unsafe;"),
                new VarInsnNode(ALOAD, 3),
                new VarInsnNode(LLOAD, 0),
                virtual("sun/misc/Unsafe", "getInt", "(Ljava/lang/Object;J)I"),
                new InsnNode(POP),
                new InsnNode(RETURN));

        FieldDynamicObservationPlan plan = analyze(
                program(clazz(CALLER, List.of(), List.of(method)), clazz(
                        TARGET,
                        List.of(field(TARGET, "first", "I"), field(TARGET, "second", "I")),
                        List.of())),
                List.of());

        assertTrue(plan.observations().stream().anyMatch(observation ->
                observation.scope() == FieldObservationScope.GLOBAL
                        && observation.observerKind() == FieldDynamicBoundaryKind.UNSAFE));
    }

    @Test
    void nativeLibraryLoadingAndNativeDeclarationsFailClosedGlobally() {
        ParsedMethod load = method(
                CALLER,
                "loadNative",
                "()V",
                new LdcInsnNode("sample"),
                staticCall("java/lang/System", "loadLibrary", "(Ljava/lang/String;)V"),
                new InsnNode(RETURN));
        MethodNode nativeNode = new MethodNode(
                ASM9,
                ACC_PRIVATE | ACC_STATIC | ACC_NATIVE,
                "nativeEntry",
                "()V",
                null,
                null);
        ParsedMethod nativeMethod = new ParsedMethod(
                CALLER,
                "nativeEntry",
                "()V",
                flags(ACC_PRIVATE | ACC_STATIC | ACC_NATIVE),
                List.of(),
                List.of(),
                List.of(),
                false,
                0,
                0,
                nativeNode);

        FieldDynamicObservationPlan plan = analyze(
                program(clazz(CALLER, List.of(), List.of(load, nativeMethod))),
                List.of());

        assertTrue(plan.observations().stream().anyMatch(observation ->
                observation.scope() == FieldObservationScope.GLOBAL
                        && observation.observerKind() == FieldDynamicBoundaryKind.NATIVE_JNI));
    }

    @Test
    void bytecodeDefinitionApisAreGlobalButOrdinaryClassLoadingIsNot() {
        String loader = "sample/DefiningLoader";
        ParsedMethod lookupDefinition = method(
                CALLER,
                "hiddenDefinition",
                "([BLjava/lang/Object;Z[Ljava/lang/invoke/MethodHandles$Lookup$ClassOption;)V",
                staticCall(
                        "java/lang/invoke/MethodHandles",
                        "lookup",
                        "()Ljava/lang/invoke/MethodHandles$Lookup;"),
                new VarInsnNode(ALOAD, 0),
                new VarInsnNode(ALOAD, 1),
                new VarInsnNode(ILOAD, 2),
                new VarInsnNode(ALOAD, 3),
                virtual(
                        "java/lang/invoke/MethodHandles$Lookup",
                        "defineHiddenClassWithClassData",
                        "([BLjava/lang/Object;Z[Ljava/lang/invoke/MethodHandles$Lookup$ClassOption;)"
                                + "Ljava/lang/invoke/MethodHandles$Lookup;"),
                new InsnNode(POP),
                new InsnNode(RETURN));
        ParsedMethod subclassDefinition = method(
                CALLER,
                "loaderDefinition",
                "(Lsample/DefiningLoader;[B)V",
                new VarInsnNode(ALOAD, 0),
                new VarInsnNode(ALOAD, 1),
                new InsnNode(ICONST_0),
                new VarInsnNode(ALOAD, 1),
                new InsnNode(ARRAYLENGTH),
                virtual(loader, "defineClass", "([BII)Ljava/lang/Class;"),
                new InsnNode(POP),
                new InsnNode(RETURN));
        ParsedMethod secureDefinition = method(
                CALLER,
                "secureDefinition",
                "(Ljava/security/SecureClassLoader;Ljava/lang/String;[BLjava/security/CodeSource;)V",
                new VarInsnNode(ALOAD, 0),
                new VarInsnNode(ALOAD, 1),
                new VarInsnNode(ALOAD, 2),
                new InsnNode(ICONST_0),
                new VarInsnNode(ALOAD, 2),
                new InsnNode(ARRAYLENGTH),
                new VarInsnNode(ALOAD, 3),
                virtual(
                        "java/security/SecureClassLoader",
                        "defineClass",
                        "(Ljava/lang/String;[BIILjava/security/CodeSource;)Ljava/lang/Class;"),
                new InsnNode(POP),
                new InsnNode(RETURN));
        ParsedMethod unknownOwnerDefinition = method(
                CALLER,
                "unknownOwnerDefinition",
                "(Lexternal/MaybeLoader;[B)V",
                new VarInsnNode(ALOAD, 0),
                new VarInsnNode(ALOAD, 1),
                virtual(
                        "external/MaybeLoader",
                        "defineClass",
                        "([B)Ljava/lang/Class;"),
                new InsnNode(POP),
                new InsnNode(RETURN));
        ParsedMethod ordinaryLoading = method(
                CALLER,
                "ordinaryLoading",
                "(Ljava/lang/ClassLoader;)V",
                new LdcInsnNode("sample.Target"),
                staticCall(
                        "java/lang/Class",
                        "forName",
                        "(Ljava/lang/String;)Ljava/lang/Class;"),
                new InsnNode(POP),
                new VarInsnNode(ALOAD, 0),
                new LdcInsnNode("sample.Target"),
                virtual(
                        "java/lang/ClassLoader",
                        "loadClass",
                        "(Ljava/lang/String;)Ljava/lang/Class;"),
                new InsnNode(POP),
                new InsnNode(RETURN));

        FieldDynamicObservationPlan plan = analyze(
                program(
                        clazz(
                                CALLER,
                                List.of(),
                                List.of(
                                        lookupDefinition,
                                        subclassDefinition,
                                        secureDefinition,
                                        unknownOwnerDefinition,
                                        ordinaryLoading)),
                        clazz(loader, "java/lang/ClassLoader", List.of(), List.of())),
                List.of());

        assertTrue(plan.observations().stream().anyMatch(observation ->
                observation.methodKey().contains("#hiddenDefinition!")
                        && observation.scope() == FieldObservationScope.GLOBAL
                        && observation.observerKind()
                                == FieldDynamicBoundaryKind.DYNAMIC_CLASS_LOADING));
        assertTrue(plan.observations().stream().anyMatch(observation ->
                observation.methodKey().contains("#loaderDefinition!")
                        && observation.scope() == FieldObservationScope.GLOBAL
                        && observation.observerKind()
                                == FieldDynamicBoundaryKind.DYNAMIC_CLASS_LOADING));
        assertTrue(plan.observations().stream().anyMatch(observation ->
                observation.methodKey().contains("#secureDefinition!")
                        && observation.scope() == FieldObservationScope.GLOBAL
                        && observation.observerKind()
                                == FieldDynamicBoundaryKind.DYNAMIC_CLASS_LOADING));
        assertTrue(plan.observations().stream().anyMatch(observation ->
                observation.methodKey().contains("#unknownOwnerDefinition!")
                        && observation.scope() == FieldObservationScope.GLOBAL
                        && observation.observerKind()
                                == FieldDynamicBoundaryKind.DYNAMIC_CLASS_LOADING));
        assertFalse(plan.observations().stream().anyMatch(observation ->
                observation.methodKey().contains("#ordinaryLoading!")));
    }

    @Test
    void indirectDefinitionHandleAndUnknownBootstrapTargetsFailClosed() {
        String loader = "sample/DefiningLoader";
        Handle lambdaBootstrap = new Handle(
                H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory",
                "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;",
                false);
        Handle definition = new Handle(
                H_INVOKEVIRTUAL,
                loader,
                "defineClass",
                "([BII)Ljava/lang/Class;",
                false);
        Handle unknownBootstrap = new Handle(
                H_INVOKESTATIC,
                "external/Bootstrap",
                "bootstrap",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                        + "Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                false);
        ParsedMethod method = method(
                CALLER,
                "indirectDefinitions",
                "()V",
                new InvokeDynamicInsnNode(
                        "run",
                        "()Ljava/lang/Runnable;",
                        lambdaBootstrap,
                        definition),
                new InsnNode(POP),
                new InvokeDynamicInsnNode(
                        "external",
                        "()Ljava/lang/Object;",
                        unknownBootstrap),
                new InsnNode(POP),
                new InsnNode(RETURN));

        FieldDynamicObservationPlan plan = new FieldUseAnalyzer()
                .analyze(program(
                        clazz(CALLER, List.of(), List.of(method)),
                        clazz(loader, "java/lang/ClassLoader", List.of(), List.of())))
                .dynamicObservationPlan();

        assertTrue(plan.observations().stream().anyMatch(observation ->
                observation.observerKind() == FieldDynamicBoundaryKind.DYNAMIC_CLASS_LOADING
                        && observation.scope() == FieldObservationScope.GLOBAL));
        assertTrue(plan.observations().stream().anyMatch(observation ->
                observation.observerKind() == FieldDynamicBoundaryKind.METHOD_HANDLE
                        && observation.scope() == FieldObservationScope.GLOBAL));
    }

    @Test
    void unknownCondyBootstrapAndNestedMethodHandleArgumentFailClosed() {
        Handle unknownBootstrap = new Handle(
                H_INVOKESTATIC,
                "external/Constants",
                "bootstrap",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;)"
                        + "Ljava/lang/Object;",
                false);
        Handle unknownArgument = new Handle(
                H_INVOKESTATIC,
                "external/Observer",
                "observe",
                "()V",
                false);
        ConstantDynamic constant = new ConstantDynamic(
                "value",
                "Ljava/lang/Object;",
                unknownBootstrap,
                unknownArgument);
        ParsedMethod method = method(
                CALLER,
                "unknownCondy",
                "()V",
                new LdcInsnNode(constant),
                new InsnNode(POP),
                new InsnNode(RETURN));

        FieldDynamicObservationPlan plan = new FieldUseAnalyzer()
                .analyze(program(clazz(CALLER, List.of(), List.of(method))))
                .dynamicObservationPlan();

        assertTrue(plan.observations().stream().anyMatch(observation ->
                observation.observerKind() == FieldDynamicBoundaryKind.METHOD_HANDLE
                        && observation.scope() == FieldObservationScope.GLOBAL));
    }

    @Test
    void customBootstrapTargetsFailClosedWhileParsedArgumentsToKnownJdkBootstrapStaySafe() {
        String bootstrapOwner = "sample/Bootstrap";
        String bootstrapDescriptor =
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                        + "Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;";
        ParsedMethod bootstrap = method(
                bootstrapOwner,
                "bootstrap",
                bootstrapDescriptor,
                new TypeInsnNode(NEW, "java/lang/invoke/ConstantCallSite"),
                new InsnNode(DUP),
                new FieldInsnNode(
                        GETSTATIC,
                        bootstrapOwner,
                        "target",
                        "Ljava/lang/invoke/MethodHandle;"),
                new MethodInsnNode(
                        INVOKESPECIAL,
                        "java/lang/invoke/ConstantCallSite",
                        "<init>",
                        "(Ljava/lang/invoke/MethodHandle;)V",
                        false),
                new InsnNode(ARETURN));
        ParsedMethod harmlessOverload = method(
                bootstrapOwner,
                "mismatch",
                "()V",
                new InsnNode(RETURN));
        ParsedMethod argumentBody = method(
                bootstrapOwner,
                "argument",
                "()V",
                new InsnNode(RETURN));
        Handle parsedBootstrap = new Handle(
                H_INVOKESTATIC,
                bootstrapOwner,
                "bootstrap",
                bootstrapDescriptor,
                false);
        Handle parsedArgument = new Handle(
                H_INVOKESTATIC,
                bootstrapOwner,
                "argument",
                "()V",
                false);
        Handle descriptorMismatch = new Handle(
                H_INVOKESTATIC,
                bootstrapOwner,
                "mismatch",
                bootstrapDescriptor,
                false);
        Handle lambdaBootstrap = new Handle(
                H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory",
                "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;",
                false);
        ParsedMethod safe = method(
                CALLER,
                "parsedBootstrap",
                "()V",
                new InvokeDynamicInsnNode(
                        "safe",
                        "()Ljava/lang/Object;",
                        parsedBootstrap,
                        parsedArgument),
                new InsnNode(POP),
                new InsnNode(RETURN));
        ParsedMethod mismatch = method(
                CALLER,
                "mismatchedBootstrap",
                "()V",
                new InvokeDynamicInsnNode(
                        "unsafe",
                        "()Ljava/lang/Object;",
                        descriptorMismatch),
                new InsnNode(POP),
                new InsnNode(RETURN));
        ParsedMethod safeJdk = method(
                CALLER,
                "knownJdkBootstrap",
                "()V",
                new InvokeDynamicInsnNode(
                        "safe",
                        "()Ljava/lang/Object;",
                        lambdaBootstrap,
                        parsedArgument),
                new InsnNode(POP),
                new InsnNode(RETURN));

        FieldDynamicObservationPlan plan = new FieldUseAnalyzer()
                .analyze(program(
                        clazz(CALLER, List.of(), List.of(safe, mismatch, safeJdk)),
                        clazz(
                                bootstrapOwner,
                                List.of(field(
                                        bootstrapOwner,
                                        "target",
                                        "Ljava/lang/invoke/MethodHandle;")),
                                List.of(bootstrap, harmlessOverload, argumentBody))))
                .dynamicObservationPlan();

        assertTrue(plan.observations().stream().anyMatch(observation ->
                observation.methodKey().contains("#parsedBootstrap!")
                        && observation.observerKind() == FieldDynamicBoundaryKind.METHOD_HANDLE
                        && observation.scope() == FieldObservationScope.GLOBAL));
        assertTrue(plan.observations().stream().anyMatch(observation ->
                observation.methodKey().contains("#mismatchedBootstrap!")
                        && observation.observerKind() == FieldDynamicBoundaryKind.METHOD_HANDLE
                        && observation.scope() == FieldObservationScope.GLOBAL));
        assertFalse(plan.observations().stream().anyMatch(observation ->
                observation.methodKey().contains("#knownJdkBootstrap!")));
    }

    private ParsedMethod unsafeExternalChain() {
        return method(
                CALLER,
                "unsafeExternal",
                "()V",
                new LdcInsnNode(Type.getObjectType(EXTERNAL)),
                new LdcInsnNode("remote"),
                virtual("java/lang/Class", "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;"),
                new VarInsnNode(ASTORE, 0),
                new FieldInsnNode(GETSTATIC, "sun/misc/Unsafe", "theUnsafe", "Lsun/misc/Unsafe;"),
                new VarInsnNode(ALOAD, 0),
                virtual("sun/misc/Unsafe", "staticFieldBase", "(Ljava/lang/reflect/Field;)Ljava/lang/Object;"),
                new VarInsnNode(ASTORE, 1),
                new FieldInsnNode(GETSTATIC, "sun/misc/Unsafe", "theUnsafe", "Lsun/misc/Unsafe;"),
                new VarInsnNode(ALOAD, 0),
                virtual("sun/misc/Unsafe", "staticFieldOffset", "(Ljava/lang/reflect/Field;)J"),
                new VarInsnNode(LSTORE, 2),
                new FieldInsnNode(GETSTATIC, "sun/misc/Unsafe", "theUnsafe", "Lsun/misc/Unsafe;"),
                new VarInsnNode(ALOAD, 1),
                new VarInsnNode(LLOAD, 2),
                virtual("sun/misc/Unsafe", "getInt", "(Ljava/lang/Object;J)I"),
                new InsnNode(POP),
                new InsnNode(RETURN));
    }

    private ParsedMethod mixedReflectionField() {
        LabelNode parameter = new LabelNode();
        LabelNode joined = new LabelNode();
        return method(
                CALLER,
                "mixedField",
                "(ZLjava/lang/reflect/Field;)V",
                new VarInsnNode(ILOAD, 0),
                new JumpInsnNode(IFEQ, parameter),
                new LdcInsnNode(Type.getObjectType(TARGET)),
                new LdcInsnNode("secret"),
                virtual("java/lang/Class", "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;"),
                new VarInsnNode(ASTORE, 2),
                new JumpInsnNode(GOTO, joined),
                parameter,
                new VarInsnNode(ALOAD, 1),
                new VarInsnNode(ASTORE, 2),
                joined,
                new VarInsnNode(ALOAD, 2),
                new InsnNode(ACONST_NULL),
                virtual("java/lang/reflect/Field", "get", "(Ljava/lang/Object;)Ljava/lang/Object;"),
                new InsnNode(POP),
                new InsnNode(RETURN));
    }

    private ParsedMethod mixedMethodHandle() {
        LabelNode parameter = new LabelNode();
        LabelNode joined = new LabelNode();
        return method(
                CALLER,
                "mixedMethodHandle",
                "(ZLjava/lang/invoke/MethodHandle;)V",
                new VarInsnNode(ILOAD, 0),
                new JumpInsnNode(IFEQ, parameter),
                new LdcInsnNode(new Handle(H_GETSTATIC, TARGET, "secret", "I", false)),
                new VarInsnNode(ASTORE, 2),
                new JumpInsnNode(GOTO, joined),
                parameter,
                new VarInsnNode(ALOAD, 1),
                new VarInsnNode(ASTORE, 2),
                joined,
                new VarInsnNode(ALOAD, 2),
                virtual("java/lang/invoke/MethodHandle", "invokeExact", "()I"),
                new InsnNode(POP),
                new InsnNode(RETURN));
    }

    private ParsedMethod mixedVarHandle() {
        LabelNode parameter = new LabelNode();
        LabelNode joined = new LabelNode();
        return method(
                CALLER,
                "mixedVarHandle",
                "(ZLjava/lang/invoke/VarHandle;)V",
                new VarInsnNode(ILOAD, 0),
                new JumpInsnNode(IFEQ, parameter),
                staticCall("java/lang/invoke/MethodHandles", "lookup", "()Ljava/lang/invoke/MethodHandles$Lookup;"),
                new LdcInsnNode(Type.getObjectType(TARGET)),
                new LdcInsnNode("secret"),
                new FieldInsnNode(GETSTATIC, "java/lang/Integer", "TYPE", "Ljava/lang/Class;"),
                virtual(
                        "java/lang/invoke/MethodHandles$Lookup",
                        "findStaticVarHandle",
                        "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/VarHandle;"),
                new VarInsnNode(ASTORE, 2),
                new JumpInsnNode(GOTO, joined),
                parameter,
                new VarInsnNode(ALOAD, 1),
                new VarInsnNode(ASTORE, 2),
                joined,
                new VarInsnNode(ALOAD, 2),
                virtual("java/lang/invoke/VarHandle", "get", "()I"),
                new InsnNode(POP),
                new InsnNode(RETURN));
    }

    private ParsedMethod mixedUnsafeOffset() {
        LabelNode parameter = new LabelNode();
        LabelNode joined = new LabelNode();
        return method(
                CALLER,
                "mixedUnsafe",
                "(ZJ)V",
                new VarInsnNode(ILOAD, 0),
                new JumpInsnNode(IFEQ, parameter),
                new FieldInsnNode(GETSTATIC, "sun/misc/Unsafe", "theUnsafe", "Lsun/misc/Unsafe;"),
                new LdcInsnNode(Type.getObjectType(TARGET)),
                new LdcInsnNode("secret"),
                virtual("java/lang/Class", "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;"),
                virtual("sun/misc/Unsafe", "staticFieldOffset", "(Ljava/lang/reflect/Field;)J"),
                new VarInsnNode(LSTORE, 3),
                new JumpInsnNode(GOTO, joined),
                parameter,
                new VarInsnNode(LLOAD, 1),
                new VarInsnNode(LSTORE, 3),
                joined,
                new FieldInsnNode(GETSTATIC, "sun/misc/Unsafe", "theUnsafe", "Lsun/misc/Unsafe;"),
                new FieldInsnNode(GETSTATIC, "sun/misc/Unsafe", "theUnsafe", "Lsun/misc/Unsafe;"),
                new LdcInsnNode(Type.getObjectType(TARGET)),
                new LdcInsnNode("secret"),
                virtual("java/lang/Class", "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;"),
                virtual("sun/misc/Unsafe", "staticFieldBase", "(Ljava/lang/reflect/Field;)Ljava/lang/Object;"),
                new VarInsnNode(LLOAD, 3),
                virtual("sun/misc/Unsafe", "getInt", "(Ljava/lang/Object;J)I"),
                new InsnNode(POP),
                new InsnNode(RETURN));
    }

    private FieldDynamicObservationPlan analyze(
            ParsedProgram input,
            List<ParsedProgram> classpath) {
        return new FieldDynamicObserverAnalyzer().analyze(input, classpath);
    }

    private ParsedClass target(String owner, String fieldName) {
        return clazz(owner, List.of(field(owner, fieldName, "I")), List.of());
    }

    private ParsedClass clazz(
            String owner,
            List<ParsedField> fields,
            List<ParsedMethod> methods) {
        return clazz(owner, "java/lang/Object", fields, methods);
    }

    private ParsedClass clazz(
            String owner,
            String superName,
            List<ParsedField> fields,
            List<ParsedMethod> methods) {
        ClassNode node = new ClassNode();
        node.name = owner;
        node.superName = superName;
        return new ParsedClass(
                owner,
                flags(ACC_PUBLIC),
                61,
                0,
                superName,
                List.of(),
                fields,
                methods,
                owner + ".class",
                "fixture",
                node);
    }

    private ParsedField field(String owner, String name, String descriptor) {
        return new ParsedField(
                owner,
                name,
                descriptor,
                flags(ACC_PRIVATE | ACC_STATIC),
                null);
    }

    private ParsedMethod method(
            String owner,
            String name,
            String descriptor,
            AbstractInsnNode... instructions) {
        MethodNode node = new MethodNode(
                ASM9,
                ACC_PRIVATE | ACC_STATIC,
                name,
                descriptor,
                null,
                null);
        for (AbstractInsnNode instruction : instructions) {
            node.instructions.add(instruction);
        }
        return new ParsedMethod(
                owner,
                name,
                descriptor,
                flags(ACC_PRIVATE | ACC_STATIC),
                List.of(),
                List.of(),
                List.of(),
                true,
                0,
                0,
                node);
    }

    private MethodInsnNode virtual(String owner, String name, String descriptor) {
        return new MethodInsnNode(INVOKEVIRTUAL, owner, name, descriptor, false);
    }

    private MethodInsnNode staticCall(String owner, String name, String descriptor) {
        return new MethodInsnNode(INVOKESTATIC, owner, name, descriptor, false);
    }

    private ParsedProgram program(ParsedClass... classes) {
        return new ParsedProgram(List.of(classes));
    }

    private AccessFlags flags(int access) {
        return new AccessFlags(access);
    }

    private record SetView<T>(java.util.Set<T> values) {
        static <T> SetView<T> of(T value) {
            return new SetView<>(java.util.Set.of(value));
        }

        static <T> SetView<T> of(List<T> values) {
            return new SetView<>(java.util.Set.copyOf(values));
        }
    }
}
