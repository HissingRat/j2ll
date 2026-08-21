package xyz.melodysky.packaging;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static xyz.melodysky.testsupport.NativeFieldInternalizationFixtures.plan;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import xyz.melodysky.analysis.field.FieldId;
import xyz.melodysky.analysis.field.FieldInternalizationReason;
import xyz.melodysky.analysis.field.FieldInternalizationStatus;
import xyz.melodysky.analysis.field.NativeFieldConstant;
import xyz.melodysky.analysis.field.NativeFieldInternalizationDecision;
import xyz.melodysky.analysis.field.NativeFieldInternalizationPlan;
import xyz.melodysky.analysis.field.NativeFieldInternalizationStorage;

class InternalizedConstantFieldClassTransformTest implements Opcodes {
    private static final String OWNER = "fixture/Constants";

    @Test
    void removesExactPrimitiveAndStringConstantValueDeclarations() {
        FieldId number = new FieldId(OWNER, "NUMBER", "I");
        FieldId text = new FieldId(OWNER, "TEXT", "Ljava/lang/String;");
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V17, ACC_PUBLIC, OWNER, null, "java/lang/Object", null);
        writer.visitField(
                        ACC_PRIVATE | ACC_STATIC | ACC_FINAL,
                        number.name(),
                        number.descriptor(),
                        null,
                        Integer.valueOf(42))
                .visitEnd();
        writer.visitField(
                        ACC_PRIVATE | ACC_STATIC | ACC_FINAL,
                        text.name(),
                        text.descriptor(),
                        null,
                        "secret-value")
                .visitEnd();
        writer.visitField(
                        ACC_PRIVATE | ACC_STATIC | ACC_FINAL,
                        "retained",
                        "I",
                        null,
                        Integer.valueOf(9))
                .visitEnd();
        writer.visitEnd();

        NativeFieldInternalizationPlan plan = plan(
                List.of(
                        decision(number, Integer.valueOf(42)),
                        decision(text, "secret-value")));
        InternalizedFieldTransformResult result =
                new InternalizedFieldClassTransform().apply(
                        writer.toByteArray(),
                        OWNER,
                        plan);
        ClassNode output = read(result.classBytes());

        assertTrue(result.diagnostics().isEmpty());
        assertEquals(
                List.of(number.fieldKey(), text.fieldKey()),
                result.removedFieldKeys().stream().sorted().toList());
        assertFalse(output.fields.stream().anyMatch(field ->
                field.name.equals(number.name()) || field.name.equals(text.name())));
        assertTrue(output.fields.stream().anyMatch(field ->
                field.name.equals("retained")));
    }

    @Test
    void failsClosedWhenConstantValueChangedAfterPlanning() {
        FieldId number = new FieldId(OWNER, "NUMBER", "I");
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V17, ACC_PUBLIC, OWNER, null, "java/lang/Object", null);
        writer.visitField(
                        ACC_PRIVATE | ACC_STATIC | ACC_FINAL,
                        number.name(),
                        number.descriptor(),
                        null,
                        Integer.valueOf(43))
                .visitEnd();
        writer.visitEnd();
        byte[] input = writer.toByteArray();

        InternalizedFieldTransformResult result =
                new InternalizedFieldClassTransform().apply(
                        input,
                        OWNER,
                        plan(List.of(
                                decision(number, Integer.valueOf(42)))));

        assertArrayEquals(input, result.classBytes());
        assertTrue(result.removedFieldKeys().isEmpty());
        assertFalse(result.diagnostics().isEmpty());
    }

    private NativeFieldInternalizationDecision decision(
            FieldId field,
            Object value) {
        return new NativeFieldInternalizationDecision(
                field,
                FieldInternalizationStatus.INTERNALIZED,
                NativeFieldInternalizationStorage.COMPILE_TIME_CONSTANT,
                Optional.empty(),
                NativeFieldConstant.from(field.descriptor(), value),
                List.of(),
                List.of(FieldInternalizationReason.FIELD_CONSTANT_INTERNALIZATION_ELIGIBLE));
    }

    private ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        return node;
    }
}
