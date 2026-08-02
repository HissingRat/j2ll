package xyz.melodysky.analysis.field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;
import xyz.melodysky.analysis.hierarchy.AnalysisWorld;
import xyz.melodysky.frontend.classfile.ParsedClass;
import xyz.melodysky.frontend.classfile.ParsedField;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.frontend.classfile.ParsedProgram;
import xyz.melodysky.jvm.AccessFlags;

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

    private ParsedProgram program(ParsedField field, ParsedMethod... methods) {
        ClassNode node = new ClassNode();
        node.name = OWNER;
        node.superName = "java/lang/Object";
        ParsedClass parsedClass = new ParsedClass(
                OWNER,
                flags(ACC_PUBLIC),
                61,
                0,
                "java/lang/Object",
                List.of(),
                List.of(field),
                List.of(methods),
                OWNER + ".class",
                "fixture",
                node);
        return new ParsedProgram(List.of(parsedClass));
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
        MethodNode node = new MethodNode(
                ASM9,
                ACC_PRIVATE | ACC_STATIC,
                name,
                "()V",
                null,
                null);
        for (AbstractInsnNode instruction : instructions) {
            node.instructions.add(instruction);
        }
        return new ParsedMethod(
                owner,
                name,
                "()V",
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
