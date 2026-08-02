package xyz.melodysky.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodNode;
import xyz.melodysky.analysis.field.FieldAccessSite;
import xyz.melodysky.analysis.field.FieldCodeOrigin;
import xyz.melodysky.analysis.field.FieldId;
import xyz.melodysky.analysis.field.FieldInternalizationReason;
import xyz.melodysky.analysis.field.FieldInternalizationStatus;
import xyz.melodysky.analysis.field.FieldReferenceKind;
import xyz.melodysky.analysis.field.NativeFieldInternalizationDecision;
import xyz.melodysky.analysis.field.NativeFieldInternalizationPlan;
import xyz.melodysky.analysis.field.NativeFieldInternalizationStorage;
import xyz.melodysky.analysis.field.NativeFieldConstant;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.jvm.AccessFlags;
import xyz.melodysky.ir.model.NativeFieldSlotRef;
import xyz.melodysky.analysis.field.NativeFieldStorageKind;
import xyz.melodysky.packaging.MethodRewriteDecision;
import xyz.melodysky.packaging.MethodRewriteStrategy;
import xyz.melodysky.packaging.NativeRegistrationEntry;
import xyz.melodysky.toolchain.NativeImplementationPath;
import xyz.melodysky.toolchain.NativeImplementationPlan;
import xyz.melodysky.toolchain.NativeMethodImplementation;

class FieldInternalizationFinalPlanValidatorTest implements Opcodes {
    private static final FieldId FIELD = new FieldId("pkg/State", "counter", "I");
    private static final FieldId REFERENCE_FIELD =
            new FieldId("pkg/State", "key", "Ljavax/crypto/SecretKey;");
    private static final String SLOT = "j2ll_nfs_00112233445566778899aabbccddeeff";
    private static final String REFERENCE_SLOT =
            "j2ll_nfs_ffeeddccbbaa99887766554433221100";
    private static final String METHOD_KEY = "pkg/State#access!()V";
    private static final String SLOT_MARKER = "native-slot:" + new NativeFieldSlotRef(
            NativeFieldStorageKind.INT,
            SLOT,
            -1).encoded();

    private final FieldInternalizationFinalPlanValidator validator =
            new FieldInternalizationFinalPlanValidator();

    @Test
    void acceptsLlvmImplementationWithMatchingNativeSlotMarkerAndNoRawFieldKey() {
        NativeImplementationPlan implementations = new NativeImplementationPlan(List.of(
                implementation(
                        NativeImplementationPath.LLVM_NATIVE_PATH,
                        List.of(SLOT_MARKER))));

        assertTrue(validator.validate(fieldPlan(), implementations).isEmpty());
    }

    @Test
    void acceptsReferenceFieldOnlyOnLlvmPathWithMatchingSlotMarker() {
        NativeFieldInternalizationPlan fieldPlan =
                fieldPlan(REFERENCE_FIELD, REFERENCE_SLOT);
        String referenceSlotMarker = "native-slot:" + new NativeFieldSlotRef(
                NativeFieldStorageKind.REFERENCE,
                REFERENCE_SLOT,
                0).encoded();
        NativeImplementationPlan implementations = new NativeImplementationPlan(List.of(
                implementation(
                        NativeImplementationPath.LLVM_NATIVE_PATH,
                        List.of(referenceSlotMarker))));

        assertTrue(validator.validate(fieldPlan, implementations).isEmpty());
    }

    @Test
    void rejectsMissingOrOrdinaryTemplateFinalImplementationPath() {
        var missing = validator.validate(
                fieldPlan(),
                new NativeImplementationPlan(List.of()));
        NativeFieldInternalizationPlan referencePlan =
                fieldPlan(REFERENCE_FIELD, REFERENCE_SLOT);
        String referenceSlotMarker = "native-slot:" + new NativeFieldSlotRef(
                NativeFieldStorageKind.REFERENCE,
                REFERENCE_SLOT,
                0).encoded();
        var ordinaryTemplate = validator.validate(
                referencePlan,
                new NativeImplementationPlan(List.of(implementation(
                        NativeImplementationPath.TEMPLATE_JNI_PATH,
                        List.of(referenceSlotMarker)))));

        assertEquals(1, missing.size());
        assertEquals(1, ordinaryTemplate.size());
        assertTrue(missing.get(0).message().contains(
                "final native implementation is missing"));
        assertTrue(ordinaryTemplate.get(0).message().contains(
                "is not LLVM_NATIVE_PATH"));
        assertEquals("FIELD_INTERNALIZATION_FINAL_PLAN_MISMATCH", missing.get(0).code().value());
    }

    @Test
    void rejectsWrongSlotMarkerAndRawJvmFieldMarker() {
        NativeImplementationPlan implementations = new NativeImplementationPlan(List.of(
                implementation(
                        NativeImplementationPath.LLVM_NATIVE_PATH,
                        List.of("native-slot:j2ll_nfs_wrong", FIELD.fieldKey()))));

        var diagnostics = validator.validate(fieldPlan(), implementations);

        assertEquals(2, diagnostics.size());
        assertTrue(diagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("does not consume " + SLOT_MARKER)));
        assertTrue(diagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("raw JVM field access survived")));
        assertTrue(diagnostics.stream().allMatch(diagnostic ->
                diagnostic.code().value().equals("FIELD_INTERNALIZATION_FINAL_PLAN_MISMATCH")));
    }

    @Test
    void rechecksConstantAccessorFinalPathAndRejectsRawFieldSurvival() {
        NativeFieldInternalizationPlan constantPlan =
                constantFieldPlan();

        assertTrue(validator.validate(
                        constantPlan,
                        new NativeImplementationPlan(List.of(implementation(
                                NativeImplementationPath.LLVM_NATIVE_PATH,
                                List.of()))))
                .isEmpty());
        var nonLlvm = validator.validate(
                constantPlan,
                new NativeImplementationPlan(List.of(implementation(
                        NativeImplementationPath.TEMPLATE_JNI_PATH,
                        List.of()))));
        var rawField = validator.validate(
                constantPlan,
                new NativeImplementationPlan(List.of(implementation(
                        NativeImplementationPath.LLVM_NATIVE_PATH,
                        List.of(FIELD.fieldKey())))));

        assertEquals(1, nonLlvm.size());
        assertTrue(nonLlvm.get(0).message().contains("constant-folded field accessor"));
        assertEquals(1, rawField.size());
        assertTrue(rawField.get(0).message().contains(
                "raw JVM constant field access survived"));
    }

    private NativeFieldInternalizationPlan fieldPlan() {
        return fieldPlan(FIELD, SLOT);
    }

    private NativeFieldInternalizationPlan fieldPlan(
            FieldId field,
            String slot) {
        FieldAccessSite access = new FieldAccessSite(
                field,
                METHOD_KEY,
                field.owner(),
                "access",
                true,
                FieldCodeOrigin.INPUT,
                FieldReferenceKind.BYTECODE_STATIC_READ,
                field.owner(),
                0,
                false);
        return new NativeFieldInternalizationPlan(List.of(new NativeFieldInternalizationDecision(
                field,
                FieldInternalizationStatus.INTERNALIZED,
                Optional.of(slot),
                List.of(access),
                List.of(FieldInternalizationReason.FIELD_INTERNALIZATION_ELIGIBLE))));
    }

    private NativeFieldInternalizationPlan constantFieldPlan() {
        FieldAccessSite access = new FieldAccessSite(
                FIELD,
                METHOD_KEY,
                FIELD.owner(),
                "access",
                true,
                FieldCodeOrigin.INPUT,
                FieldReferenceKind.BYTECODE_STATIC_READ,
                FIELD.owner(),
                0,
                false);
        return new NativeFieldInternalizationPlan(List.of(
                new NativeFieldInternalizationDecision(
                        FIELD,
                        FieldInternalizationStatus.INTERNALIZED,
                        NativeFieldInternalizationStorage.COMPILE_TIME_CONSTANT,
                        Optional.empty(),
                        NativeFieldConstant.from("I", Integer.valueOf(17)),
                        List.of(access),
                        List.of(FieldInternalizationReason.FIELD_CONSTANT_INTERNALIZATION_ELIGIBLE))));
    }

    private NativeMethodImplementation implementation(
            NativeImplementationPath path,
            List<String> fieldKeys) {
        return implementation(path, "TEST", fieldKeys);
    }

    private NativeMethodImplementation implementation(
            NativeImplementationPath path,
            String reasonCode,
            List<String> fieldKeys) {
        ParsedMethod method = new ParsedMethod(
                FIELD.owner(),
                "access",
                "()V",
                new AccessFlags(ACC_PRIVATE | ACC_STATIC),
                List.of(),
                List.of(),
                List.of(),
                true,
                0,
                0,
                new MethodNode(ASM9, ACC_PRIVATE | ACC_STATIC, "access", "()V", null, null));
        MethodRewriteDecision decision = new MethodRewriteDecision(
                method,
                MethodRewriteStrategy.NATIVE_ORIGINAL,
                FIELD.owner(),
                Optional.empty(),
                "TEST");
        NativeRegistrationEntry entry = new NativeRegistrationEntry(
                FIELD.owner(),
                method.name(),
                method.descriptor(),
                "j2ll_test_access");
        return new NativeMethodImplementation(
                entry,
                decision,
                path,
                path == NativeImplementationPath.LLVM_NATIVE_PATH
                        ? Optional.of("j2ll_test_impl")
                        : Optional.empty(),
                reasonCode,
                true,
                true,
                fieldKeys,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Optional.empty());
    }
}
