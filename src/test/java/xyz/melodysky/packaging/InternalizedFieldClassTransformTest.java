package xyz.melodysky.packaging;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.TypeReference;
import org.objectweb.asm.tree.ClassNode;
import xyz.melodysky.analysis.field.FieldId;
import xyz.melodysky.analysis.field.FieldInternalizationReason;
import xyz.melodysky.analysis.field.FieldInternalizationStatus;
import xyz.melodysky.analysis.field.NativeFieldInternalizationDecision;
import xyz.melodysky.analysis.field.NativeFieldInternalizationPlan;

class InternalizedFieldClassTransformTest implements Opcodes {
    private static final String OWNER = "pkg/State";
    private static final FieldId APPROVED = new FieldId(OWNER, "counter", "I");

    private final InternalizedFieldClassTransform transform = new InternalizedFieldClassTransform();

    @Test
    void removesOnlyPlanApprovedFieldAndLeavesClassLoadable() throws Exception {
        byte[] input = classBytes(ACC_PRIVATE | ACC_STATIC, true);

        InternalizedFieldTransformResult result = transform.apply(input, OWNER, approvedPlan());
        ClassNode output = read(result.classBytes());

        assertTrue(result.diagnostics().isEmpty());
        assertEquals(List.of(APPROVED.fieldKey()), result.removedFieldKeys());
        assertFalse(output.fields.stream().anyMatch(field -> field.name.equals("counter")));
        assertTrue(output.fields.stream().anyMatch(field -> field.name.equals("retained")));
        assertTrue(output.methods.stream().anyMatch(method -> method.name.equals("answer")));
        Class<?> defined = new ByteArrayClassLoader().define(result.classBytes());
        assertEquals(7, defined.getMethod("answer").invoke(null));
    }

    @Test
    void removesAllSupportedStaticPrimitiveAndReferenceDescriptors() {
        List<String> descriptors = List.of(
                "Z", "B", "S", "C", "I", "J", "F", "D",
                "Ljava/lang/Object;", "[I");
        List<FieldId> fields = java.util.stream.IntStream.range(0, descriptors.size())
                .mapToObj(index -> new FieldId(
                        OWNER,
                        "state" + index,
                        descriptors.get(index)))
                .toList();
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V17, ACC_PUBLIC, OWNER, null, "java/lang/Object", null);
        fields.forEach(field -> writer.visitField(
                        ACC_PRIVATE | ACC_STATIC,
                        field.name(),
                        field.descriptor(),
                        null,
                        null)
                .visitEnd());
        writer.visitEnd();
        NativeFieldInternalizationPlan plan = new NativeFieldInternalizationPlan(
                java.util.stream.IntStream.range(0, fields.size())
                        .mapToObj(index -> new NativeFieldInternalizationDecision(
                                fields.get(index),
                                FieldInternalizationStatus.INTERNALIZED,
                                Optional.of("j2ll_nfs_slot_" + index),
                                List.of(),
                                List.of(FieldInternalizationReason.FIELD_INTERNALIZATION_ELIGIBLE)))
                        .toList());

        InternalizedFieldTransformResult result =
                transform.apply(writer.toByteArray(), OWNER, plan);

        assertTrue(result.diagnostics().isEmpty());
        assertEquals(fields.size(), result.removedFieldKeys().size());
        assertTrue(read(result.classBytes()).fields.isEmpty());
        new ByteArrayClassLoader().define(result.classBytes());
    }

    @Test
    void failsClosedAndReturnsOriginalBytesWhenApprovedFieldNoLongerMatchesContract() {
        byte[] input = classBytes(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, true);

        InternalizedFieldTransformResult result = transform.apply(input, OWNER, approvedPlan());

        assertArrayEquals(input, result.classBytes());
        assertTrue(result.removedFieldKeys().isEmpty());
        assertFalse(result.diagnostics().isEmpty());
        assertTrue(result.diagnostics().stream().allMatch(diagnostic ->
                diagnostic.code().equals(PackagingDiagnostics.FIELD_INTERNALIZATION_REWRITE_FAILED)));
    }

    @Test
    void failsClosedWhenApprovedFieldIsMissing() {
        byte[] input = classBytes(ACC_PRIVATE | ACC_STATIC, false);

        InternalizedFieldTransformResult result = transform.apply(input, OWNER, approvedPlan());

        assertArrayEquals(input, result.classBytes());
        assertTrue(result.removedFieldKeys().isEmpty());
        assertEquals(1, result.diagnostics().size());
    }

    @Test
    void planForDifferentOwnerIsExactNoOp() {
        byte[] input = classBytes(ACC_PRIVATE | ACC_STATIC, true);
        NativeFieldInternalizationPlan otherOwnerPlan = plan(
                new FieldId("pkg/Other", "counter", "I"));

        InternalizedFieldTransformResult result = transform.apply(input, OWNER, otherOwnerPlan);

        assertArrayEquals(input, result.classBytes());
        assertTrue(result.removedFieldKeys().isEmpty());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void rechecksSyntheticSignatureAnnotationsAndTypeAnnotationsBeforeRemoval() {
        int fieldTypeReference = TypeReference.newTypeReference(TypeReference.FIELD).getValue();
        List<byte[]> structurallyUnsafe = List.of(
                classBytes(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC, true),
                classBytes(ACC_PRIVATE | ACC_STATIC | ACC_ENUM, true),
                classBytes(ACC_PRIVATE | ACC_STATIC, true, "TI;", ignored -> {}, false),
                classBytes(ACC_PRIVATE | ACC_STATIC, true, null, field ->
                        field.visitAnnotation("Lpkg/Visible;", true).visitEnd(), false),
                classBytes(ACC_PRIVATE | ACC_STATIC, true, null, field ->
                        field.visitAnnotation("Lpkg/Invisible;", false).visitEnd(), false),
                classBytes(ACC_PRIVATE | ACC_STATIC, true, null, field ->
                        field.visitTypeAnnotation(
                                        fieldTypeReference,
                                        null,
                                        "Lpkg/VisibleType;",
                                        true)
                                .visitEnd(), false),
                classBytes(ACC_PRIVATE | ACC_STATIC, true, null, field ->
                        field.visitTypeAnnotation(
                                        fieldTypeReference,
                                        null,
                                        "Lpkg/InvisibleType;",
                                        false)
                                .visitEnd(), false));

        for (byte[] input : structurallyUnsafe) {
            InternalizedFieldTransformResult result = transform.apply(input, OWNER, approvedPlan());
            assertArrayEquals(input, result.classBytes());
            assertTrue(result.removedFieldKeys().isEmpty());
            assertFalse(result.diagnostics().isEmpty());
        }
    }

    @Test
    void allowsUnrelatedSourceClassInitializerAfterPlannerApprovedTheField() {
        byte[] input = classBytes(
                ACC_PRIVATE | ACC_STATIC,
                true,
                null,
                ignored -> {},
                true);

        InternalizedFieldTransformResult result =
                transform.apply(input, OWNER, approvedPlan());

        assertTrue(result.diagnostics().isEmpty());
        assertEquals(List.of(APPROVED.fieldKey()), result.removedFieldKeys());
        assertFalse(read(result.classBytes()).fields.stream()
                .anyMatch(field -> field.name.equals("counter")));
        assertTrue(read(result.classBytes()).methods.stream()
                .anyMatch(method -> method.name.equals("<clinit>")));
    }

    @Test
    void allowsLoaderInitializerGeneratedAfterSourceContractWasCaptured() {
        byte[] rewritten = classBytes(
                ACC_PRIVATE | ACC_STATIC,
                true,
                null,
                ignored -> {},
                true);

        InternalizedFieldTransformResult result =
                transform.apply(rewritten, OWNER, approvedPlan(), false);

        assertTrue(result.diagnostics().isEmpty());
        assertEquals(List.of(APPROVED.fieldKey()), result.removedFieldKeys());
        assertFalse(read(result.classBytes()).fields.stream()
                .anyMatch(field -> field.name.equals("counter")));
        assertTrue(read(result.classBytes()).methods.stream()
                .anyMatch(method -> method.name.equals("<clinit>")));
    }

    private byte[] classBytes(int approvedAccess, boolean includeApproved) {
        return classBytes(approvedAccess, includeApproved, null, ignored -> {}, false);
    }

    private byte[] classBytes(
            int approvedAccess,
            boolean includeApproved,
            String signature,
            Consumer<FieldVisitor> fieldCustomizer,
            boolean classInitializer) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V17, ACC_PUBLIC, OWNER, null, "java/lang/Object", null);
        if (includeApproved) {
            FieldVisitor field = writer.visitField(approvedAccess, "counter", "I", signature, null);
            fieldCustomizer.accept(field);
            field.visitEnd();
        }
        writer.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "retained", "I", null, 11).visitEnd();

        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();

        MethodVisitor answer = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "answer", "()I", null, null);
        answer.visitCode();
        answer.visitIntInsn(BIPUSH, 7);
        answer.visitInsn(IRETURN);
        answer.visitMaxs(1, 0);
        answer.visitEnd();
        if (classInitializer) {
            MethodVisitor initializer = writer.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
            initializer.visitCode();
            initializer.visitInsn(RETURN);
            initializer.visitMaxs(0, 0);
            initializer.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        return node;
    }

    private NativeFieldInternalizationPlan approvedPlan() {
        return plan(APPROVED);
    }

    private NativeFieldInternalizationPlan plan(FieldId field) {
        return new NativeFieldInternalizationPlan(List.of(new NativeFieldInternalizationDecision(
                field,
                FieldInternalizationStatus.INTERNALIZED,
                Optional.of("j2ll_nfs_00112233445566778899aabbccddeeff"),
                List.of(),
                List.of(FieldInternalizationReason.FIELD_INTERNALIZATION_ELIGIBLE))));
    }

    private static final class ByteArrayClassLoader extends ClassLoader {
        private Class<?> define(byte[] bytes) {
            return defineClass(null, bytes, 0, bytes.length);
        }
    }
}
