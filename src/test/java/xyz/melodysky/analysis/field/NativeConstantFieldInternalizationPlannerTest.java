package xyz.melodysky.analysis.field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import xyz.melodysky.analysis.hierarchy.AnalysisWorld;
import xyz.melodysky.analysis.reflection.ReflectionPlan;
import xyz.melodysky.analysis.reflection.StaticReflectionResolver;
import xyz.melodysky.frontend.classfile.ParsedClass;
import xyz.melodysky.frontend.classfile.ParsedField;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.frontend.classfile.ParsedProgram;
import xyz.melodysky.jvm.AccessFlags;
import xyz.melodysky.runtime.metadata.RuntimeMetadataIndexBuilder;

class NativeConstantFieldInternalizationPlannerTest implements Opcodes {
    private static final String OWNER = "fixture/Constants";

    @Test
    void approvesPrimitiveConstantReadWithoutAllocatingRuntimeStorage() {
        FieldId fieldId = new FieldId(OWNER, "KEY_SIZE", "I");
        ParsedProgram program = program(
                field(fieldId, Integer.valueOf(128)),
                method(
                        OWNER,
                        "read",
                        new FieldInsnNode(GETSTATIC, OWNER, "KEY_SIZE", "I"),
                        new InsnNode(POP),
                        new InsnNode(RETURN)));

        NativeFieldInternalizationPlan plan = plan(
                program,
                ignored -> FieldAccessImplementationPath.LLVM_NATIVE_PATH);
        NativeFieldInternalizationDecision decision =
                plan.decisionFor(fieldId).orElseThrow();

        assertTrue(decision.constantFolded());
        assertEquals(
                NativeFieldInternalizationStorage.COMPILE_TIME_CONSTANT,
                decision.storage());
        assertTrue(decision.nativeSlotId().isEmpty());
        assertEquals(128, decision.constant().orElseThrow().intValue());
        assertEquals(0, plan.referenceSidecarSize());
        assertTrue(plan.nativeStoredFields().isEmpty());
    }

    @Test
    void constantBootstrapsGetStaticFinalImplicitTargetBlocksRemoval() {
        FieldId fieldId = new FieldId(OWNER, "BOOTSTRAP_VALUE", "I");
        Handle bootstrap = new Handle(
                H_INVOKESTATIC,
                "java/lang/invoke/ConstantBootstraps",
                "getStaticFinal",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;",
                false);
        ConstantDynamic constant = new ConstantDynamic(
                fieldId.name(),
                fieldId.descriptor(),
                bootstrap);
        ParsedProgram program = program(
                field(fieldId, Integer.valueOf(91)),
                method(
                        OWNER,
                        "readBootstrap",
                        new LdcInsnNode(constant),
                        new InsnNode(POP),
                        new InsnNode(RETURN)));

        NativeFieldInternalizationPlan plan = plan(
                program,
                ignored -> FieldAccessImplementationPath.LLVM_NATIVE_PATH);
        NativeFieldInternalizationDecision decision =
                plan.decisionFor(fieldId).orElseThrow();

        assertFalse(decision.internalized());
        assertEquals(1, decision.accesses().size());
        assertTrue(decision.reasons().contains(
                FieldInternalizationReason.METHOD_HANDLE_FIELD_REFERENCE));
    }

    @Test
    void rejectsPrimitiveConstantWriteAndNonNativeReader() {
        FieldId fieldId = new FieldId(OWNER, "LIMIT", "I");
        ParsedProgram writeProgram = program(
                field(fieldId, Integer.valueOf(7)),
                method(
                        OWNER,
                        "write",
                        new InsnNode(ICONST_1),
                        new FieldInsnNode(PUTSTATIC, OWNER, "LIMIT", "I"),
                        new InsnNode(RETURN)));

        NativeFieldInternalizationDecision writeDecision = plan(
                        writeProgram,
                        ignored -> FieldAccessImplementationPath.LLVM_NATIVE_PATH)
                .decisionFor(fieldId)
                .orElseThrow();
        assertFalse(writeDecision.internalized());
        assertTrue(writeDecision.reasons().contains(
                FieldInternalizationReason.CONSTANT_FIELD_WRITE_ACCESS));

        ParsedProgram readProgram = program(
                field(fieldId, Integer.valueOf(7)),
                method(
                        OWNER,
                        "read",
                        new FieldInsnNode(GETSTATIC, OWNER, "LIMIT", "I"),
                        new InsnNode(POP),
                        new InsnNode(RETURN)));
        NativeFieldInternalizationDecision javaDecision = plan(
                        readProgram,
                        ignored -> FieldAccessImplementationPath.NON_LLVM_PATH)
                .decisionFor(fieldId)
                .orElseThrow();
        assertFalse(javaDecision.internalized());
        assertTrue(javaDecision.reasons().contains(
                FieldInternalizationReason.ACCESS_PATH_NOT_LLVM_NATIVE));
    }

    @Test
    void removesUnreferencedStringDeclarationButRejectsIdentityChangingGetstatic() {
        FieldId fieldId = new FieldId(
                OWNER,
                "ALGORITHM",
                "Ljava/lang/String;");
        ParsedField field = field(fieldId, "AES/CBC/PKCS5Padding");
        ParsedProgram inlinedProgram = program(
                field,
                method(OWNER, "unrelated", new InsnNode(RETURN)));

        NativeFieldInternalizationDecision inlined = plan(
                        inlinedProgram,
                        ignored -> FieldAccessImplementationPath.LLVM_NATIVE_PATH)
                .decisionFor(fieldId)
                .orElseThrow();
        assertTrue(inlined.constantFolded());
        assertTrue(inlined.accesses().isEmpty());

        ParsedProgram explicitReadProgram = program(
                field,
                method(
                        OWNER,
                        "read",
                        new FieldInsnNode(
                                GETSTATIC,
                                OWNER,
                                "ALGORITHM",
                                "Ljava/lang/String;"),
                        new InsnNode(POP),
                        new InsnNode(RETURN)));
        NativeFieldInternalizationDecision explicitRead = plan(
                        explicitReadProgram,
                        ignored -> FieldAccessImplementationPath.LLVM_NATIVE_PATH)
                .decisionFor(fieldId)
                .orElseThrow();
        assertFalse(explicitRead.internalized());
        assertTrue(explicitRead.reasons().contains(
                FieldInternalizationReason.FIELD_CONSTANT_STRING_IDENTITY_UNSUPPORTED));
    }

    @Test
    void removesPrimitiveDeclarationWhenJavacAlreadyEliminatedEveryFieldReference() {
        FieldId fieldId = new FieldId(OWNER, "INLINE_LIMIT", "I");
        NativeFieldInternalizationDecision decision = plan(
                        program(
                                field(fieldId, Integer.valueOf(73)),
                                method(OWNER, "unrelated", new InsnNode(RETURN))),
                        ignored -> FieldAccessImplementationPath.UNKNOWN)
                .decisionFor(fieldId)
                .orElseThrow();

        assertTrue(decision.constantFolded());
        assertTrue(decision.accesses().isEmpty());
        assertTrue(decision.nativeSlotId().isEmpty());
    }

    @Test
    void preservesRawFloatingPointBitsAndJvmNarrowing() {
        NativeFieldConstant narrow = NativeFieldConstant.from("B", Integer.valueOf(255))
                .orElseThrow();
        int nanBits = 0x7fc01234;
        NativeFieldConstant nan = NativeFieldConstant.from(
                        "F",
                        Float.intBitsToFloat(nanBits))
                .orElseThrow();

        assertEquals(-1, narrow.intValue());
        assertEquals(nanBits, Float.floatToRawIntBits(nan.floatValue()));
    }

    @Test
    void exactCrossOwnerReflectionKeepsTheResolvedConstantField() {
        String observerOwner = "fixture/Observer";
        FieldId fieldId = new FieldId(OWNER, "REFLECTED", "I");
        ParsedField field = field(fieldId, Integer.valueOf(41));
        ParsedMethod observer = method(
                observerOwner,
                "observe",
                new LdcInsnNode(Type.getObjectType(OWNER)),
                new LdcInsnNode(fieldId.name()),
                new MethodInsnNode(
                        INVOKEVIRTUAL,
                        "java/lang/Class",
                        "getDeclaredField",
                        "(Ljava/lang/String;)Ljava/lang/reflect/Field;",
                        false),
                new VarInsnNode(ASTORE, 0),
                new VarInsnNode(ALOAD, 0),
                new InsnNode(ACONST_NULL),
                new MethodInsnNode(
                        INVOKEVIRTUAL,
                        "java/lang/reflect/Field",
                        "get",
                        "(Ljava/lang/Object;)Ljava/lang/Object;",
                        false),
                new InsnNode(POP),
                new InsnNode(RETURN));
        ParsedProgram program = new ParsedProgram(List.of(
                parsedClass(OWNER, List.of(field), List.of()),
                parsedClass(observerOwner, List.of(), List.of(observer))));
        ReflectionPlan reflectionPlan = reflectionPlan(program);

        NativeFieldInternalizationDecision decision = plan(
                        program,
                        reflectionPlan,
                        ignored -> FieldAccessImplementationPath.UNKNOWN)
                .decisionFor(fieldId)
                .orElseThrow();

        assertEquals(1, reflectionPlan.resolvedFields().size());
        assertTrue(reflectionPlan.unsupportedSites().isEmpty());
        assertFalse(decision.internalized());
        assertTrue(decision.reasons().contains(
                FieldInternalizationReason.REFLECTION_DYNAMIC_SURFACE));
    }

    @Test
    void declaredFieldLookupKeepsEverySameNameDescriptor() {
        String observerOwner = "fixture/Observer";
        FieldId intField = new FieldId(OWNER, "DUPLICATE", "I");
        FieldId longField = new FieldId(OWNER, "DUPLICATE", "J");
        ParsedMethod observer = method(
                observerOwner,
                "observe",
                new LdcInsnNode(Type.getObjectType(OWNER)),
                new LdcInsnNode("DUPLICATE"),
                new MethodInsnNode(
                        INVOKEVIRTUAL,
                        "java/lang/Class",
                        "getDeclaredField",
                        "(Ljava/lang/String;)Ljava/lang/reflect/Field;",
                        false),
                new InsnNode(POP),
                new InsnNode(RETURN));
        ParsedProgram program = new ParsedProgram(List.of(
                parsedClass(
                        OWNER,
                        List.of(
                                field(intField, Integer.valueOf(17)),
                                field(longField, Long.valueOf(19L))),
                        List.of()),
                parsedClass(observerOwner, List.of(), List.of(observer))));
        ReflectionPlan reflectionPlan = reflectionPlan(program);

        NativeFieldInternalizationPlan plan = plan(
                program,
                reflectionPlan,
                ignored -> FieldAccessImplementationPath.UNKNOWN);

        assertEquals(2, reflectionPlan.resolvedFields().size());
        for (FieldId field : List.of(intField, longField)) {
            NativeFieldInternalizationDecision decision = plan.decisionFor(field).orElseThrow();
            assertFalse(decision.internalized());
            assertTrue(decision.reasons().contains(
                    FieldInternalizationReason.REFLECTION_DYNAMIC_SURFACE));
        }
    }

    @Test
    void unresolvedFieldScanIsOwnerScopedButMethodScanDoesNotBlockFields() {
        String observerOwner = "fixture/Observer";
        FieldId fieldId = new FieldId(OWNER, "INLINE_ONLY", "I");
        ParsedField field = field(fieldId, Integer.valueOf(73));

        ParsedMethod fieldScan = method(
                observerOwner,
                "scanFields",
                new LdcInsnNode(Type.getObjectType(OWNER)),
                new MethodInsnNode(
                        INVOKEVIRTUAL,
                        "java/lang/Class",
                        "getDeclaredFields",
                        "()[Ljava/lang/reflect/Field;",
                        false),
                new InsnNode(POP),
                new InsnNode(RETURN));
        ParsedProgram fieldScanProgram = new ParsedProgram(List.of(
                parsedClass(OWNER, List.of(field), List.of()),
                parsedClass(observerOwner, List.of(), List.of(fieldScan))));
        NativeFieldInternalizationDecision blocked = plan(
                        fieldScanProgram,
                        reflectionPlan(fieldScanProgram),
                        ignored -> FieldAccessImplementationPath.UNKNOWN)
                .decisionFor(fieldId)
                .orElseThrow();
        assertFalse(blocked.internalized());
        assertTrue(blocked.reasons().contains(
                FieldInternalizationReason.REFLECTION_DYNAMIC_SURFACE));

        ParsedMethod methodScan = method(
                observerOwner,
                "scanMethods",
                new LdcInsnNode(Type.getObjectType(OWNER)),
                new MethodInsnNode(
                        INVOKEVIRTUAL,
                        "java/lang/Class",
                        "getDeclaredMethods",
                        "()[Ljava/lang/reflect/Method;",
                        false),
                new InsnNode(POP),
                new InsnNode(RETURN));
        ParsedProgram methodScanProgram = new ParsedProgram(List.of(
                parsedClass(OWNER, List.of(field), List.of()),
                parsedClass(observerOwner, List.of(), List.of(methodScan))));
        NativeFieldInternalizationDecision unaffected = plan(
                        methodScanProgram,
                        reflectionPlan(methodScanProgram),
                        ignored -> FieldAccessImplementationPath.UNKNOWN)
                .decisionFor(fieldId)
                .orElseThrow();
        assertTrue(unaffected.constantFolded(), unaffected.reasons().toString());
    }

    @Test
    void knownExternalFieldLookupAndScanDoNotBlockInputFieldsButUnknownOwnerDoes() {
        String observerOwner = "fixture/Observer";
        String externalOwner = "external/Missing";
        FieldId fieldId = new FieldId(OWNER, "INLINE_ONLY", "I");
        ParsedField field = field(fieldId, Integer.valueOf(73));
        ParsedMethod externalLookup = method(
                observerOwner,
                "externalLookup",
                new LdcInsnNode(Type.getObjectType(externalOwner)),
                new LdcInsnNode("externalField"),
                new MethodInsnNode(
                        INVOKEVIRTUAL,
                        "java/lang/Class",
                        "getDeclaredField",
                        "(Ljava/lang/String;)Ljava/lang/reflect/Field;",
                        false),
                new VarInsnNode(ASTORE, 0),
                new VarInsnNode(ALOAD, 0),
                new InsnNode(ACONST_NULL),
                new MethodInsnNode(
                        INVOKEVIRTUAL,
                        "java/lang/reflect/Field",
                        "get",
                        "(Ljava/lang/Object;)Ljava/lang/Object;",
                        false),
                new InsnNode(POP),
                new InsnNode(RETURN));
        ParsedMethod externalScan = method(
                observerOwner,
                "externalScan",
                new LdcInsnNode(Type.getObjectType(externalOwner)),
                new MethodInsnNode(
                        INVOKEVIRTUAL,
                        "java/lang/Class",
                        "getDeclaredFields",
                        "()[Ljava/lang/reflect/Field;",
                        false),
                new VarInsnNode(ASTORE, 0),
                new VarInsnNode(ALOAD, 0),
                new InsnNode(ICONST_0),
                new InsnNode(AALOAD),
                new VarInsnNode(ASTORE, 1),
                new VarInsnNode(ALOAD, 1),
                new InsnNode(ACONST_NULL),
                new MethodInsnNode(
                        INVOKEVIRTUAL,
                        "java/lang/reflect/Field",
                        "get",
                        "(Ljava/lang/Object;)Ljava/lang/Object;",
                        false),
                new InsnNode(POP),
                new InsnNode(RETURN));
        ParsedProgram externalProgram = new ParsedProgram(List.of(
                parsedClass(OWNER, List.of(field), List.of()),
                parsedClass(
                        observerOwner,
                        List.of(),
                        List.of(externalLookup, externalScan))));
        ReflectionPlan externalPlan = reflectionPlan(externalProgram);
        assertEquals(2, externalPlan.unsupportedSites().size());
        NativeFieldInternalizationDecision unaffected = plan(
                        externalProgram,
                        externalPlan,
                        ignored -> FieldAccessImplementationPath.UNKNOWN)
                .decisionFor(fieldId)
                .orElseThrow();
        assertTrue(unaffected.constantFolded(), unaffected.reasons().toString());

        ParsedMethod unknownScan = method(
                observerOwner,
                "unknownScan",
                "(Ljava/lang/Class;)V",
                new VarInsnNode(ALOAD, 0),
                new MethodInsnNode(
                        INVOKEVIRTUAL,
                        "java/lang/Class",
                        "getDeclaredFields",
                        "()[Ljava/lang/reflect/Field;",
                        false),
                new InsnNode(POP),
                new InsnNode(RETURN));
        ParsedProgram unknownProgram = new ParsedProgram(List.of(
                parsedClass(OWNER, List.of(field), List.of()),
                parsedClass(observerOwner, List.of(), List.of(unknownScan))));
        ReflectionPlan unknownPlan = reflectionPlan(unknownProgram);
        assertEquals(1, unknownPlan.unsupportedSites().size());
        NativeFieldInternalizationDecision blocked = plan(
                        unknownProgram,
                        unknownPlan,
                        ignored -> FieldAccessImplementationPath.UNKNOWN)
                .decisionFor(fieldId)
                .orElseThrow();
        assertFalse(blocked.internalized());
        assertTrue(blocked.reasons().contains(
                FieldInternalizationReason.REFLECTION_DYNAMIC_SURFACE));
    }

    @Test
    void unknownReflectFieldReceiverBlocksAllInputFields() {
        String observerOwner = "fixture/Observer";
        FieldId fieldId = new FieldId(OWNER, "INLINE_ONLY", "I");
        ParsedField field = field(fieldId, Integer.valueOf(73));
        ParsedMethod unknownAccess = method(
                observerOwner,
                "unknownAccess",
                "(Ljava/lang/reflect/Field;)V",
                new VarInsnNode(ALOAD, 0),
                new InsnNode(ACONST_NULL),
                new MethodInsnNode(
                        INVOKEVIRTUAL,
                        "java/lang/reflect/Field",
                        "get",
                        "(Ljava/lang/Object;)Ljava/lang/Object;",
                        false),
                new InsnNode(POP),
                new InsnNode(RETURN));
        ParsedProgram program = new ParsedProgram(List.of(
                parsedClass(OWNER, List.of(field), List.of()),
                parsedClass(observerOwner, List.of(), List.of(unknownAccess))));
        ReflectionPlan reflectionPlan = reflectionPlan(program);
        assertTrue(reflectionPlan.unsupportedSites().isEmpty());

        NativeFieldInternalizationDecision decision = plan(
                        program,
                        reflectionPlan,
                        ignored -> FieldAccessImplementationPath.UNKNOWN)
                .decisionFor(fieldId)
                .orElseThrow();
        assertFalse(decision.internalized());
        assertTrue(decision.reasons().contains(
                FieldInternalizationReason.REFLECTION_DYNAMIC_SURFACE));
    }

    @Test
    void unmodelledStackMutationCannotInventAnExactReflectionOwner() {
        String observerOwner = "fixture/Observer";
        FieldId fieldId = new FieldId(OWNER, "SECRET", "I");
        ParsedField field = field(fieldId, Integer.valueOf(91));
        ParsedMethod dynamicLookup = method(
                observerOwner,
                "dynamicLookup",
                new LdcInsnNode(Type.getObjectType("fixture/Unrelated")),
                new FieldInsnNode(
                        PUTSTATIC,
                        "fixture/Holder",
                        "EXACT",
                        "Ljava/lang/Class;"),
                new FieldInsnNode(
                        GETSTATIC,
                        "fixture/Holder",
                        "DYNAMIC",
                        "Ljava/lang/Class;"),
                new LdcInsnNode(fieldId.name()),
                new MethodInsnNode(
                        INVOKEVIRTUAL,
                        "java/lang/Class",
                        "getDeclaredField",
                        "(Ljava/lang/String;)Ljava/lang/reflect/Field;",
                        false),
                new InsnNode(POP),
                new InsnNode(RETURN));
        ParsedProgram program = new ParsedProgram(List.of(
                parsedClass(OWNER, List.of(field), List.of()),
                parsedClass(observerOwner, List.of(), List.of(dynamicLookup))));

        ReflectionPlan reflectionPlan = reflectionPlan(program);
        NativeFieldInternalizationDecision decision = plan(
                        program,
                        reflectionPlan,
                        ignored -> FieldAccessImplementationPath.UNKNOWN)
                .decisionFor(fieldId)
                .orElseThrow();

        assertFalse(reflectionPlan.unsupportedSites().isEmpty());
        assertFalse(decision.internalized());
        assertTrue(decision.reasons().contains(
                FieldInternalizationReason.REFLECTION_DYNAMIC_SURFACE));
    }

    private NativeFieldInternalizationPlan plan(
            ParsedProgram program,
            FieldAccessPathResolver resolver) {
        return new NativeFieldInternalizationPlanner().plan(
                new FieldUseAnalyzer().analyze(program),
                AnalysisWorld.CLOSED_WORLD,
                true,
                91L,
                resolver);
    }

    private NativeFieldInternalizationPlan plan(
            ParsedProgram program,
            ReflectionPlan ignoredReflectionPlan,
            FieldAccessPathResolver resolver) {
        return plan(program, resolver);
    }

    private ReflectionPlan reflectionPlan(ParsedProgram program) {
        var metadata = new RuntimeMetadataIndexBuilder().build(program);
        assertTrue(metadata.artifact().isPresent(), metadata.diagnostics().toString());
        return new StaticReflectionResolver().resolve(
                program,
                metadata.artifact().orElseThrow());
    }

    private ParsedProgram program(ParsedField field, ParsedMethod... methods) {
        return new ParsedProgram(List.of(parsedClass(
                OWNER,
                List.of(field),
                List.of(methods))));
    }

    private ParsedClass parsedClass(
            String owner,
            List<ParsedField> fields,
            List<ParsedMethod> methods) {
        ClassNode node = new ClassNode();
        node.version = V17;
        node.access = ACC_PUBLIC;
        node.name = owner;
        node.superName = "java/lang/Object";
        fields.forEach(field -> node.fields.add(new FieldNode(
                field.accessFlags().value(),
                field.name(),
                field.descriptor(),
                field.signature(),
                field.constantValue())));
        methods.forEach(method -> node.methods.add(method.methodNode()));
        return new ParsedClass(
                owner,
                flags(ACC_PUBLIC),
                61,
                0,
                "java/lang/Object",
                List.of(),
                fields,
                methods,
                owner + ".class",
                "fixture",
                node);
    }

    private ParsedField field(FieldId field, Object constantValue) {
        return new ParsedField(
                field.owner(),
                field.name(),
                field.descriptor(),
                flags(ACC_PRIVATE | ACC_STATIC | ACC_FINAL),
                null,
                constantValue,
                false);
    }

    private ParsedMethod method(
            String owner,
            String name,
            AbstractInsnNode... instructions) {
        return method(owner, name, "()V", instructions);
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

    private AccessFlags flags(int access) {
        return new AccessFlags(access);
    }
}
