package xyz.melodysky.analysis.field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import xyz.melodysky.analysis.hierarchy.AnalysisWorld;
import xyz.melodysky.analysis.world.WholeProgramAnalysisScope;
import xyz.melodysky.frontend.classfile.ParsedClass;
import xyz.melodysky.frontend.classfile.ParsedField;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.frontend.classfile.ParsedProgram;
import xyz.melodysky.jvm.AccessFlags;

class NativeFieldInternalizationPlannerTest implements Opcodes {
    private static final String OWNER = "pkg/State";
    private static final FieldId INT_FIELD = new FieldId(OWNER, "state", "I");

    private final FieldUseAnalyzer analyzer = new FieldUseAnalyzer();
    private final NativeFieldInternalizationPlanner planner = new NativeFieldInternalizationPlanner();

    @Test
    void internalizesPrivateStaticIntAndLongWhenEveryAccessIsSameOwnerLlvmNative() {
        ParsedField intField = field(OWNER, "state", "I", ACC_PRIVATE | ACC_STATIC);
        ParsedField longField = field(OWNER, "wide", "J", ACC_PRIVATE | ACC_STATIC);
        ParsedMethod method = method(
                OWNER,
                "touch",
                new FieldInsnNode(GETSTATIC, OWNER, "state", "I"),
                new InsnNode(POP),
                new InsnNode(ICONST_1),
                new FieldInsnNode(PUTSTATIC, OWNER, "state", "I"),
                new FieldInsnNode(GETSTATIC, OWNER, "wide", "J"),
                new InsnNode(POP2),
                new InsnNode(LCONST_0),
                new FieldInsnNode(PUTSTATIC, OWNER, "wide", "J"),
                new InsnNode(RETURN));
        ParsedProgram program = program(parsedClass(OWNER, List.of(intField, longField), List.of(method)));
        FieldUseIndex index = analyzer.analyze(program);

        NativeFieldInternalizationPlan first = plan(index, AnalysisWorld.CLOSED_WORLD, true, 91L, llvmOnly());
        NativeFieldInternalizationPlan repeated = plan(index, AnalysisWorld.CLOSED_WORLD, true, 91L, llvmOnly());
        NativeFieldInternalizationPlan differentSeed = plan(index, AnalysisWorld.CLOSED_WORLD, true, 92L, llvmOnly());

        assertEquals(2, first.internalizedFields().size());
        assertEquals(first, repeated);
        assertNotEquals(
                first.decisionFor(INT_FIELD).orElseThrow().nativeSlotId(),
                differentSeed.decisionFor(INT_FIELD).orElseThrow().nativeSlotId());
        assertTrue(first.internalizedFields().stream()
                .allMatch(decision -> decision.reasons().equals(
                        List.of(FieldInternalizationReason.FIELD_INTERNALIZATION_ELIGIBLE))));
    }

    @Test
    void internalizesEveryRequestedStaticPrimitiveAndReferenceStorageKind() {
        List<String> descriptors = List.of(
                "Z",
                "B",
                "S",
                "C",
                "I",
                "J",
                "F",
                "D",
                "Ljava/lang/Object;",
                "[I");
        ArrayList<ParsedField> fields = new ArrayList<>();
        ArrayList<AbstractInsnNode> instructions = new ArrayList<>();
        for (int index = 0; index < descriptors.size(); index++) {
            String descriptor = descriptors.get(index);
            String name = "field" + index;
            fields.add(field(OWNER, name, descriptor, ACC_PRIVATE | ACC_STATIC));
            instructions.add(new FieldInsnNode(GETSTATIC, OWNER, name, descriptor));
            instructions.add(new InsnNode(
                    descriptor.equals("J") || descriptor.equals("D") ? POP2 : POP));
        }
        instructions.add(new InsnNode(RETURN));
        NativeFieldInternalizationPlan plan = plan(
                analyzer.analyze(program(parsedClass(
                        OWNER,
                        fields,
                        List.of(method(
                                OWNER,
                                "readAllKinds",
                                instructions.toArray(AbstractInsnNode[]::new)))))),
                AnalysisWorld.CLOSED_WORLD,
                true,
                17L,
                llvmOnly());

        assertEquals(descriptors.size(), plan.internalizedFields().size());
        assertEquals(2, plan.referenceSidecarSize());
        List<Integer> referenceIndices = plan.internalizedFields().stream()
                .filter(decision -> plan.storageKind(decision).reference())
                .map(plan::referenceIndex)
                .sorted()
                .toList();
        assertEquals(List.of(0, 1), referenceIndices);
    }

    @Test
    void diversifiesReferenceSidecarOrderPerSeedAndKeepsEachOwnerDense() {
        int fieldsPerOwner = 8;
        ParsedProgram program = program(
                referenceOwner("pkg/ReferenceA", fieldsPerOwner),
                referenceOwner("pkg/ReferenceB", fieldsPerOwner));
        FieldUseIndex index = analyzer.analyze(program);

        NativeFieldInternalizationPlan first = plan(
                index,
                AnalysisWorld.CLOSED_WORLD,
                true,
                0x51decafL,
                llvmOnly());
        NativeFieldInternalizationPlan repeated = plan(
                index,
                AnalysisWorld.CLOSED_WORLD,
                true,
                0x51decafL,
                llvmOnly());
        NativeFieldInternalizationPlan differentSeed = plan(
                index,
                AnalysisWorld.CLOSED_WORLD,
                true,
                0x51decafL + 1,
                llvmOnly());

        assertEquals(first.referenceIndicesByOwner(), repeated.referenceIndicesByOwner());
        assertNotEquals(
                first.referenceIndicesByOwner(),
                differentSeed.referenceIndicesByOwner());
        assertEquals(fieldsPerOwner, first.referenceSidecarSize());
        assertEquals(
                List.of("pkg/ReferenceA", "pkg/ReferenceB"),
                first.referenceIndicesByOwner().keySet().stream().toList());
        for (Map<FieldId, Integer> ownerIndices
                : first.referenceIndicesByOwner().values()) {
            assertEquals(fieldsPerOwner, ownerIndices.size());
            assertEquals(
                    java.util.stream.IntStream.range(0, fieldsPerOwner)
                            .boxed()
                            .toList(),
                    ownerIndices.values().stream().sorted().toList());
        }
    }

    @Test
    void keepsFieldWhenAnyAccessFinalPathIsNotLlvmNativeOrUnknown() {
        ParsedProgram program = candidateProgram();
        FieldUseIndex index = analyzer.analyze(program);

        NativeFieldInternalizationDecision nonLlvm = decision(
                plan(index, AnalysisWorld.CLOSED_WORLD, true, 1L, ignored -> FieldAccessImplementationPath.NON_LLVM_PATH));
        NativeFieldInternalizationDecision unknown = decision(
                plan(index, AnalysisWorld.CLOSED_WORLD, true, 1L, ignored -> FieldAccessImplementationPath.UNKNOWN));

        assertReason(nonLlvm, FieldInternalizationReason.ACCESS_PATH_NOT_LLVM_NATIVE);
        assertReason(unknown, FieldInternalizationReason.ACCESS_PATH_UNKNOWN);
        assertFalse(nonLlvm.internalized());
        assertTrue(nonLlvm.nativeSlotId().isEmpty());
    }

    @Test
    void keepsReferenceFieldWhenAnAccessorIsNotLlvmLowered() {
        String descriptor = "Ljavax/crypto/SecretKey;";
        FieldId fieldId = new FieldId(OWNER, "key", descriptor);
        ParsedField field = field(
                OWNER,
                fieldId.name(),
                descriptor,
                ACC_PRIVATE | ACC_STATIC);
        ParsedMethod fallbackAccessor = method(
                OWNER,
                "readFromFallback",
                new FieldInsnNode(
                        GETSTATIC,
                        OWNER,
                        fieldId.name(),
                        descriptor),
                new InsnNode(POP),
                new InsnNode(RETURN));
        FieldUseIndex index = analyzer.analyze(program(parsedClass(
                OWNER,
                List.of(field),
                List.of(fallbackAccessor))));

        NativeFieldInternalizationDecision decision = plan(
                        index,
                        AnalysisWorld.CLOSED_WORLD,
                        true,
                        1L,
                        ignored -> FieldAccessImplementationPath.NON_LLVM_PATH)
                .decisionFor(fieldId)
                .orElseThrow();

        assertFalse(decision.internalized());
        assertReason(
                decision,
                FieldInternalizationReason.ACCESS_PATH_NOT_LLVM_NATIVE);
    }

    @Test
    void keepsPrimitiveFieldWhenAccessorIsNotLlvmLowered() {
        FieldUseIndex index = analyzer.analyze(candidateProgram());

        NativeFieldInternalizationDecision decision = decision(plan(
                index,
                AnalysisWorld.CLOSED_WORLD,
                true,
                1L,
                ignored -> FieldAccessImplementationPath.NON_LLVM_PATH));

        assertFalse(decision.internalized());
        assertReason(
                decision,
                FieldInternalizationReason.ACCESS_PATH_NOT_LLVM_NATIVE);
    }

    @Test
    void rejectsInstanceAccessorSoInheritedCallsCannotPartitionStaticStateByReceiverClass() {
        ParsedField field = field(
                OWNER,
                "state",
                "Ljava/lang/Object;",
                ACC_PRIVATE | ACC_STATIC);
        ParsedMethod instanceAccessor = method(
                ACC_PUBLIC,
                OWNER,
                "readFromInstance",
                new FieldInsnNode(GETSTATIC, OWNER, "state", "Ljava/lang/Object;"),
                new InsnNode(POP),
                new InsnNode(RETURN));
        ParsedClass ownerClass = parsedClass(
                OWNER,
                List.of(field),
                List.of(instanceAccessor));
        ParsedClass childClass = parsedClass(
                "pkg/StateChild",
                List.of(),
                List.of(),
                OWNER,
                List.of(),
                "pkg/StateChild.class");

        FieldUseIndex index = analyzer.analyze(program(ownerClass, childClass));
        NativeFieldInternalizationDecision decision = plan(
                        index,
                        AnalysisWorld.CLOSED_WORLD,
                        true,
                        1L,
                        llvmOnly())
                .decisionFor(new FieldId(OWNER, "state", "Ljava/lang/Object;"))
                .orElseThrow();

        assertFalse(index.accessesFor(decision.field()).get(0).methodStatic());
        assertReason(decision, FieldInternalizationReason.ACCESS_METHOD_NOT_STATIC);
        assertFalse(decision.internalized());
    }

    @Test
    void requiresClosedAndCompleteWorld() {
        FieldUseIndex index = analyzer.analyze(candidateProgram());

        NativeFieldInternalizationDecision partial = decision(
                plan(index, AnalysisWorld.PARTIAL_WORLD, true, 1L, llvmOnly()));
        NativeFieldInternalizationDecision incomplete = decision(
                plan(index, AnalysisWorld.CLOSED_WORLD, false, 1L, llvmOnly()));

        assertReason(partial, FieldInternalizationReason.WORLD_NOT_CLOSED);
        assertReason(incomplete, FieldInternalizationReason.WORLD_INCOMPLETE);
    }

    @Test
    void userApprovedCurrentJarScopePermitsCandidateAndIgnoresExternalUnresolvedFields() {
        ParsedClass candidate = candidateClass(
                new FieldInsnNode(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"),
                new InsnNode(POP));
        FieldUseIndex index = analyzer.analyze(program(candidate));

        NativeFieldInternalizationDecision decision = decision(plan(
                index,
                WholeProgramAnalysisScope.CURRENT_JAR_ONLY_USER_APPROVED,
                true,
                1L,
                llvmOnly()));

        assertEquals(1, index.unresolvedReferences().size());
        assertTrue(decision.internalized(), decision.reasons().toString());
    }

    @Test
    void userApprovedCurrentJarScopeStillRejectsUnresolvedInputOwnerFields() {
        ParsedClass candidate = candidateClass(
                new FieldInsnNode(GETSTATIC, OWNER, "missing", "I"),
                new InsnNode(POP));
        FieldUseIndex index = analyzer.analyze(program(candidate));

        NativeFieldInternalizationDecision decision = decision(plan(
                index,
                WholeProgramAnalysisScope.CURRENT_JAR_ONLY_USER_APPROVED,
                true,
                1L,
                llvmOnly()));

        assertReason(decision, FieldInternalizationReason.UNRESOLVED_FIELD_REFERENCE);
        assertFalse(decision.internalized());
    }

    @Test
    void unresolvedExternalFieldDoesNotPolluteUnrelatedCandidate() {
        ParsedClass candidate = candidateClass(
                new FieldInsnNode(GETSTATIC, "missing/External", "unknown", "I"),
                new InsnNode(POP));
        FieldUseIndex index = analyzer.analyze(program(candidate));

        NativeFieldInternalizationDecision decision = decision(
                plan(index, AnalysisWorld.CLOSED_WORLD, true, 1L, llvmOnly()));

        assertEquals(1, index.unresolvedReferences().size());
        assertTrue(decision.internalized(), decision.reasons().toString());
    }

    @Test
    void unresolvedInputOwnerFieldDoesNotPolluteCandidateFromAnotherOwner() {
        String unrelatedOwner = "pkg/Unrelated";
        ParsedMethod unresolvedAccess = method(
                unrelatedOwner,
                "readMissing",
                new FieldInsnNode(GETSTATIC, unrelatedOwner, "missing", "I"),
                new InsnNode(POP),
                new InsnNode(RETURN));
        FieldUseIndex index = analyzer.analyze(program(
                candidateClass(),
                parsedClass(unrelatedOwner, List.of(), List.of(unresolvedAccess))));

        NativeFieldInternalizationDecision decision = decision(
                plan(index, AnalysisWorld.CLOSED_WORLD, true, 1L, llvmOnly()));

        assertEquals(1, index.unresolvedReferences().size());
        assertTrue(decision.internalized(), decision.reasons().toString());
    }

    @Test
    void resolvesSymbolicFieldOwnerToActualDeclarationAndRejectsCrossOwnerAccess() {
        String child = "pkg/Child";
        ParsedField field = field(OWNER, "state", "I", ACC_PROTECTED | ACC_STATIC);
        ParsedClass baseClass = parsedClass(OWNER, List.of(field), List.of(), "java/lang/Object", List.of(), OWNER + ".class");
        ParsedMethod childMethod = method(
                child,
                "read",
                new FieldInsnNode(GETSTATIC, child, "state", "I"),
                new InsnNode(POP),
                new InsnNode(RETURN));
        ParsedClass childClass = parsedClass(child, List.of(), List.of(childMethod), OWNER, List.of(), child + ".class");

        FieldUseIndex index = analyzer.analyze(program(baseClass, childClass));
        FieldAccessSite access = index.accessesFor(INT_FIELD).get(0);
        NativeFieldInternalizationDecision decision = decision(
                plan(index, AnalysisWorld.CLOSED_WORLD, true, 1L, llvmOnly()));

        assertEquals(OWNER, access.field().owner());
        assertEquals(child, access.symbolicOwner());
        assertReason(decision, FieldInternalizationReason.CROSS_OWNER_FIELD_ACCESS);
        assertReason(decision, FieldInternalizationReason.FIELD_NOT_PRIVATE);
    }

    @Test
    void rejectsClasspathAccessEvenWhenResolverClaimsLlvmNative() {
        ParsedClass ownerClass = candidateClass();
        String externalOwner = "dependency/Accessor";
        ParsedMethod externalMethod = method(
                externalOwner,
                "read",
                new FieldInsnNode(GETSTATIC, OWNER, "state", "I"),
                new InsnNode(POP),
                new InsnNode(RETURN));
        ParsedProgram classpath = program(parsedClass(externalOwner, List.of(), List.of(externalMethod)));

        FieldUseIndex index = analyzer.analyze(program(ownerClass), List.of(classpath));
        NativeFieldInternalizationDecision decision = decision(
                plan(index, AnalysisWorld.CLOSED_WORLD, true, 1L, llvmOnly()));

        assertReason(decision, FieldInternalizationReason.CLASSPATH_FIELD_ACCESS);
        assertReason(decision, FieldInternalizationReason.CROSS_OWNER_FIELD_ACCESS);
    }

    @Test
    void scansFieldHandlesInLdcInvokeDynamicAndConstantDynamicBootstrapArguments() {
        Handle fieldHandle = new Handle(H_GETSTATIC, OWNER, "state", "I", false);
        Handle bootstrap = new Handle(
                H_INVOKESTATIC,
                "pkg/Bootstrap",
                "bootstrap",
                "()Ljava/lang/Object;",
                false);
        ConstantDynamic constantDynamic = new ConstantDynamic("field", "I", bootstrap, fieldHandle);
        ParsedMethod method = method(
                OWNER,
                "dynamic",
                new LdcInsnNode(fieldHandle),
                new InsnNode(POP),
                new InvokeDynamicInsnNode("read", "()V", bootstrap, fieldHandle),
                new LdcInsnNode(constantDynamic),
                new InsnNode(POP),
                new InsnNode(RETURN));
        ParsedProgram program = program(parsedClass(
                OWNER,
                List.of(field(OWNER, "state", "I", ACC_PRIVATE | ACC_STATIC)),
                List.of(method)));

        FieldUseIndex index = analyzer.analyze(program);
        NativeFieldInternalizationDecision decision = decision(
                plan(index, AnalysisWorld.CLOSED_WORLD, true, 1L, llvmOnly()));

        assertEquals(3, index.accessesFor(INT_FIELD).size());
        assertEquals(2, index.accessesFor(INT_FIELD).stream().filter(FieldAccessSite::bootstrapArgument).count());
        assertReason(decision, FieldInternalizationReason.METHOD_HANDLE_FIELD_REFERENCE);
        assertReason(decision, FieldInternalizationReason.METHOD_HANDLE_DYNAMIC_SURFACE);
    }

    @Test
    void rejectsMultiReleaseCounterpartAndNoAccessWithoutBlamingUnrelatedClassInitializer() {
        ParsedField field = field(OWNER, "state", "I", ACC_PRIVATE | ACC_STATIC);
        ParsedMethod classInitializer = method(OWNER, "<clinit>", new InsnNode(RETURN));
        ParsedClass base = parsedClass(OWNER, List.of(field), List.of(classInitializer));
        ParsedClass versioned = parsedClass(
                OWNER,
                List.of(),
                List.of(),
                "java/lang/Object",
                List.of(),
                "META-INF/versions/17/" + OWNER + ".class");

        NativeFieldInternalizationDecision decision = decision(plan(
                analyzer.analyze(program(base, versioned)),
                AnalysisWorld.CLOSED_WORLD,
                true,
                1L,
                llvmOnly()));

        assertReason(decision, FieldInternalizationReason.MULTI_RELEASE_OWNER);
        assertFalse(decision.reasons().contains(FieldInternalizationReason.OWNER_HAS_CLASS_INITIALIZER));
        assertFalse(decision.reasons().contains(FieldInternalizationReason.CLASS_INITIALIZER_ACCESS));
        assertReason(decision, FieldInternalizationReason.FIELD_HAS_NO_ACCESS);
    }

    @Test
    void unrelatedClassInitializerDoesNotBlockOtherwiseEligibleField() {
        ParsedField field = field(OWNER, "state", "I", ACC_PRIVATE | ACC_STATIC);
        ParsedMethod classInitializer = method(OWNER, "<clinit>", new InsnNode(RETURN));
        ParsedMethod access = method(
                OWNER,
                "access",
                new FieldInsnNode(GETSTATIC, OWNER, "state", "I"),
                new InsnNode(POP),
                new InsnNode(RETURN));

        NativeFieldInternalizationDecision decision = decision(plan(
                analyzer.analyze(program(parsedClass(OWNER, List.of(field), List.of(classInitializer, access)))),
                AnalysisWorld.CLOSED_WORLD,
                true,
                1L,
                llvmOnly()));

        assertTrue(decision.internalized(), decision.reasons().toString());
    }

    @Test
    void targetFieldAccessedByClassInitializerRetainsBothInitializerReasons() {
        ParsedField field = field(OWNER, "state", "I", ACC_PRIVATE | ACC_STATIC);
        ParsedMethod classInitializer = method(
                OWNER,
                "<clinit>",
                new FieldInsnNode(GETSTATIC, OWNER, "state", "I"),
                new InsnNode(POP),
                new InsnNode(RETURN));

        NativeFieldInternalizationDecision decision = decision(plan(
                analyzer.analyze(program(parsedClass(OWNER, List.of(field), List.of(classInitializer)))),
                AnalysisWorld.CLOSED_WORLD,
                true,
                1L,
                llvmOnly()));

        assertReason(decision, FieldInternalizationReason.OWNER_HAS_CLASS_INITIALIZER);
        assertReason(decision, FieldInternalizationReason.CLASS_INITIALIZER_ACCESS);
        assertFalse(decision.internalized());
    }

    @Test
    void acceptsAdditionalMultiReleaseOwnersDiscoveredFromJarEntryAudit() {
        FieldUseIndex baseIndex = analyzer.analyze(candidateProgram());
        FieldUseIndex augmented = baseIndex.withAdditionalMultiReleaseOwners(java.util.Set.of(OWNER));

        NativeFieldInternalizationDecision decision = decision(plan(
                augmented,
                AnalysisWorld.CLOSED_WORLD,
                true,
                1L,
                llvmOnly()));

        assertReason(decision, FieldInternalizationReason.MULTI_RELEASE_OWNER);
        assertEquals(augmented, augmented.withAdditionalMultiReleaseOwners(java.util.Set.of(OWNER)));
    }

    @Test
    void rejectsUnsupportedFieldDeclarationFactsWithoutDroppingOtherReasons() {
        List<ParsedField> fields = List.of(
                field(OWNER, "publicField", "I", ACC_PUBLIC | ACC_STATIC),
                field(OWNER, "instanceField", "I", ACC_PRIVATE),
                field(OWNER, "referenceField", "Ljava/lang/String;", ACC_PRIVATE | ACC_STATIC),
                field(OWNER, "finalField", "I", ACC_PRIVATE | ACC_STATIC | ACC_FINAL),
                field(OWNER, "volatileField", "I", ACC_PRIVATE | ACC_STATIC | ACC_VOLATILE),
                field(OWNER, "syntheticField", "I", ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC),
                new ParsedField(OWNER, "constantField", "I", flags(ACC_PRIVATE | ACC_STATIC), null, 0, false),
                new ParsedField(OWNER, "signatureField", "I", flags(ACC_PRIVATE | ACC_STATIC), "TI;", null, false),
                new ParsedField(OWNER, "annotatedField", "I", flags(ACC_PRIVATE | ACC_STATIC), null, null, true));
        ArrayList<AbstractInsnNode> instructions = new ArrayList<>();
        for (ParsedField field : fields) {
            instructions.add(new FieldInsnNode(GETSTATIC, OWNER, field.name(), field.descriptor()));
            instructions.add(new InsnNode(field.descriptor().equals("J") || field.descriptor().equals("D") ? POP2 : POP));
        }
        instructions.add(new InsnNode(RETURN));
        ParsedProgram program = program(parsedClass(OWNER, fields, List.of(method(
                OWNER,
                "readAll",
                instructions.toArray(AbstractInsnNode[]::new)))));

        NativeFieldInternalizationPlan plan = plan(
                analyzer.analyze(program),
                AnalysisWorld.CLOSED_WORLD,
                true,
                1L,
                llvmOnly());

        assertReason(plan, "publicField", FieldInternalizationReason.FIELD_NOT_PRIVATE);
        assertReason(plan, "instanceField", FieldInternalizationReason.FIELD_NOT_STATIC);
        assertTrue(plan.decisions().stream()
                .filter(candidate -> candidate.field().name().equals("referenceField"))
                .findFirst()
                .orElseThrow()
                .internalized());
        assertReason(plan, "finalField", FieldInternalizationReason.FIELD_FINAL);
        assertReason(plan, "volatileField", FieldInternalizationReason.FIELD_VOLATILE);
        assertReason(plan, "syntheticField", FieldInternalizationReason.FIELD_SYNTHETIC_OR_COMPILER_GENERATED);
        assertReason(plan, "constantField", FieldInternalizationReason.FIELD_HAS_CONSTANT_VALUE);
        assertReason(plan, "signatureField", FieldInternalizationReason.FIELD_HAS_SIGNATURE);
        assertReason(plan, "annotatedField", FieldInternalizationReason.FIELD_HAS_ANNOTATIONS);
    }

    @Test
    void rejectsReflectionUnsafeVarHandleFieldMethodHandleSerializationAgentAndNativeSurfaces() {
        List<BoundaryCase> cases = List.of(
                new BoundaryCase(
                        new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Class", "getDeclaredFields", "()[Ljava/lang/reflect/Field;", false),
                        FieldInternalizationReason.REFLECTION_DYNAMIC_SURFACE),
                new BoundaryCase(
                        new MethodInsnNode(INVOKEVIRTUAL, "sun/misc/Unsafe", "getInt", "(Ljava/lang/Object;J)I", false),
                        FieldInternalizationReason.UNSAFE_DYNAMIC_SURFACE),
                new BoundaryCase(
                        new MethodInsnNode(INVOKEVIRTUAL, "java/lang/invoke/VarHandle", "get", "([Ljava/lang/Object;)Ljava/lang/Object;", false),
                        FieldInternalizationReason.VAR_HANDLE_DYNAMIC_SURFACE),
                new BoundaryCase(
                        new MethodInsnNode(
                                INVOKEVIRTUAL,
                                "java/lang/invoke/MethodHandles$Lookup",
                                "findStaticGetter",
                                "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;",
                                false),
                        FieldInternalizationReason.METHOD_HANDLE_DYNAMIC_SURFACE),
                new BoundaryCase(
                        new MethodInsnNode(INVOKEVIRTUAL, "java/io/ObjectOutputStream", "defaultWriteObject", "()V", false),
                        FieldInternalizationReason.SERIALIZATION_DYNAMIC_SURFACE),
                new BoundaryCase(
                        new MethodInsnNode(INVOKEINTERFACE, "java/lang/instrument/Instrumentation", "retransformClasses", "([Ljava/lang/Class;)V", true),
                        FieldInternalizationReason.AGENT_INSTRUMENTATION_DYNAMIC_SURFACE),
                new BoundaryCase(
                        new MethodInsnNode(INVOKESTATIC, "java/lang/System", "loadLibrary", "(Ljava/lang/String;)V", false),
                        FieldInternalizationReason.NATIVE_JNI_DYNAMIC_SURFACE));

        for (BoundaryCase boundaryCase : cases) {
            ParsedClass candidate = candidateClass(boundaryCase.instruction());
            NativeFieldInternalizationDecision decision = decision(plan(
                    analyzer.analyze(program(candidate)),
                    AnalysisWorld.CLOSED_WORLD,
                    true,
                    1L,
                    llvmOnly()));
            assertReason(decision, boundaryCase.reason());
        }

        ParsedMethod nativeMethod = new ParsedMethod(
                OWNER,
                "nativeEntry",
                "()V",
                flags(ACC_PRIVATE | ACC_STATIC | ACC_NATIVE),
                List.of(),
                List.of(),
                List.of(),
                false,
                0,
                0,
                new MethodNode(ASM9, ACC_PRIVATE | ACC_STATIC | ACC_NATIVE, "nativeEntry", "()V", null, null));
        ParsedClass withNative = candidateClass();
        ParsedClass rebuilt = parsedClass(
                OWNER,
                withNative.fields(),
                List.of(withNative.methods().get(0), nativeMethod));
        NativeFieldInternalizationDecision nativeDecision = decision(plan(
                analyzer.analyze(program(rebuilt)),
                AnalysisWorld.CLOSED_WORLD,
                true,
                1L,
                llvmOnly()));
        assertReason(nativeDecision, FieldInternalizationReason.NATIVE_JNI_DYNAMIC_SURFACE);
    }

    @Test
    void dynamicClassLoadingAloneDoesNotObserveAnOtherwiseEligibleField() {
        ParsedClass candidate = candidateClass(new MethodInsnNode(
                INVOKEVIRTUAL,
                "java/lang/ClassLoader",
                "loadClass",
                "(Ljava/lang/String;)Ljava/lang/Class;",
                false));
        FieldUseIndex index = analyzer.analyze(program(candidate));

        NativeFieldInternalizationDecision decision = decision(
                plan(index, AnalysisWorld.CLOSED_WORLD, true, 1L, llvmOnly()));

        assertEquals(
                List.of(FieldDynamicBoundaryKind.DYNAMIC_CLASS_LOADING),
                index.dynamicBoundaries().stream()
                        .map(FieldDynamicBoundary::kind)
                        .toList());
        assertTrue(decision.internalized(), decision.reasons().toString());
    }

    @Test
    void dynamicBoundaryInAnotherOwnerDoesNotPolluteCandidate() {
        String unrelatedOwner = "pkg/DynamicUser";
        ParsedMethod reflectionMethod = method(
                unrelatedOwner,
                "reflect",
                new MethodInsnNode(
                        INVOKEVIRTUAL,
                        "java/lang/Class",
                        "getDeclaredFields",
                        "()[Ljava/lang/reflect/Field;",
                        false),
                new InsnNode(POP),
                new InsnNode(RETURN));
        FieldUseIndex index = analyzer.analyze(program(
                candidateClass(),
                parsedClass(unrelatedOwner, List.of(), List.of(reflectionMethod))));

        NativeFieldInternalizationDecision decision = decision(
                plan(index, AnalysisWorld.CLOSED_WORLD, true, 1L, llvmOnly()));

        assertEquals(1, index.dynamicBoundaries().size());
        assertTrue(decision.internalized(), decision.reasons().toString());
    }

    @Test
    void rejectsSerializableOwner() {
        ParsedClass serializable = parsedClass(
                OWNER,
                candidateClass().fields(),
                candidateClass().methods(),
                "java/lang/Object",
                List.of("java/io/Serializable"),
                OWNER + ".class");

        NativeFieldInternalizationDecision decision = decision(plan(
                analyzer.analyze(program(serializable)),
                AnalysisWorld.CLOSED_WORLD,
                true,
                1L,
                llvmOnly()));

        assertReason(decision, FieldInternalizationReason.OWNER_IS_SERIALIZABLE);
    }

    private ParsedProgram candidateProgram() {
        return program(candidateClass());
    }

    private ParsedClass referenceOwner(String owner, int fieldCount) {
        ArrayList<ParsedField> fields = new ArrayList<>();
        ArrayList<AbstractInsnNode> instructions = new ArrayList<>();
        for (int index = 0; index < fieldCount; index++) {
            String name = "reference" + index;
            String descriptor = index % 2 == 0
                    ? "Ljava/lang/Object;"
                    : "[Ljava/lang/String;";
            fields.add(field(owner, name, descriptor, ACC_PRIVATE | ACC_STATIC));
            instructions.add(new FieldInsnNode(GETSTATIC, owner, name, descriptor));
            instructions.add(new InsnNode(POP));
        }
        instructions.add(new InsnNode(RETURN));
        return parsedClass(
                owner,
                fields,
                List.of(method(
                        owner,
                        "readReferences",
                        instructions.toArray(AbstractInsnNode[]::new))));
    }

    private ParsedClass candidateClass(AbstractInsnNode... extraInstructions) {
        ArrayList<AbstractInsnNode> instructions = new ArrayList<>();
        instructions.add(new FieldInsnNode(GETSTATIC, OWNER, "state", "I"));
        instructions.add(new InsnNode(POP));
        instructions.addAll(List.of(extraInstructions));
        instructions.add(new InsnNode(RETURN));
        return parsedClass(
                OWNER,
                List.of(field(OWNER, "state", "I", ACC_PRIVATE | ACC_STATIC)),
                List.of(method(OWNER, "access", instructions.toArray(AbstractInsnNode[]::new))));
    }

    private NativeFieldInternalizationPlan plan(
            FieldUseIndex index,
            AnalysisWorld world,
            boolean complete,
            long seed,
            FieldAccessPathResolver resolver) {
        return planner.plan(index, world, complete, seed, resolver);
    }

    private NativeFieldInternalizationPlan plan(
            FieldUseIndex index,
            WholeProgramAnalysisScope scope,
            boolean complete,
            long seed,
            FieldAccessPathResolver resolver) {
        return planner.plan(index, scope, complete, seed, resolver);
    }

    private FieldAccessPathResolver llvmOnly() {
        return ignored -> FieldAccessImplementationPath.LLVM_NATIVE_PATH;
    }

    private NativeFieldInternalizationDecision decision(NativeFieldInternalizationPlan plan) {
        return plan.decisionFor(INT_FIELD).orElseThrow();
    }

    private void assertReason(
            NativeFieldInternalizationDecision decision,
            FieldInternalizationReason reason) {
        assertTrue(decision.reasons().contains(reason), () -> "missing " + reason + " in " + decision.reasons());
    }

    private void assertReason(
            NativeFieldInternalizationPlan plan,
            String fieldName,
            FieldInternalizationReason reason) {
        NativeFieldInternalizationDecision decision = plan.decisions().stream()
                .filter(candidate -> candidate.field().name().equals(fieldName))
                .findFirst()
                .orElseThrow();
        assertReason(decision, reason);
    }

    private ParsedProgram program(ParsedClass... classes) {
        return new ParsedProgram(List.of(classes));
    }

    private ParsedClass parsedClass(
            String owner,
            List<ParsedField> fields,
            List<ParsedMethod> methods) {
        return parsedClass(owner, fields, methods, "java/lang/Object", List.of(), owner + ".class");
    }

    private ParsedClass parsedClass(
            String owner,
            List<ParsedField> fields,
            List<ParsedMethod> methods,
            String superName,
            List<String> interfaces,
            String sourceEntry) {
        ClassNode classNode = new ClassNode();
        classNode.name = owner;
        classNode.superName = superName;
        classNode.interfaces = new ArrayList<>(interfaces);
        return new ParsedClass(
                owner,
                flags(ACC_PUBLIC),
                61,
                0,
                superName,
                interfaces,
                fields,
                methods,
                sourceEntry,
                "fixture",
                classNode);
    }

    private ParsedField field(String owner, String name, String descriptor, int access) {
        return new ParsedField(owner, name, descriptor, flags(access), null);
    }

    private ParsedMethod method(String owner, String name, AbstractInsnNode... instructions) {
        return method(ACC_PRIVATE | ACC_STATIC, owner, name, instructions);
    }

    private ParsedMethod method(
            int access,
            String owner,
            String name,
            AbstractInsnNode... instructions) {
        MethodNode methodNode = new MethodNode(ASM9, access, name, "()V", null, null);
        for (AbstractInsnNode instruction : instructions) {
            methodNode.instructions.add(instruction);
        }
        return new ParsedMethod(
                owner,
                name,
                "()V",
                flags(access),
                List.of(),
                List.of(),
                List.of(),
                true,
                0,
                0,
                methodNode);
    }

    private AccessFlags flags(int access) {
        return new AccessFlags(access);
    }

    private record BoundaryCase(AbstractInsnNode instruction, FieldInternalizationReason reason) {}
}
