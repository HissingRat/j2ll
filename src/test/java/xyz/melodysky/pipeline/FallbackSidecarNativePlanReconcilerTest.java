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
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.jvm.AccessFlags;
import xyz.melodysky.packaging.FallbackSidecarFieldAccess;
import xyz.melodysky.packaging.MethodRewriteDecision;
import xyz.melodysky.packaging.MethodRewriteStrategy;
import xyz.melodysky.packaging.NativeRegistrationEntry;
import xyz.melodysky.toolchain.NativeImplementationPath;
import xyz.melodysky.toolchain.NativeImplementationPlan;
import xyz.melodysky.toolchain.NativeMethodImplementation;

class FallbackSidecarNativePlanReconcilerTest implements Opcodes {
    private static final FieldId FIELD =
            new FieldId("pkg/State", "key", "Ljava/lang/Object;");
    private static final String METHOD_KEY = "pkg/State#access!()V";
    private static final String SLOT =
            "j2ll_nfs_00112233445566778899aabbccddeeff";

    private final FallbackSidecarNativePlanReconciler reconciler =
            new FallbackSidecarNativePlanReconciler();

    @Test
    void attachesVerifiedReferenceSlotAndFallbackMappingToEmbeddedFallback() {
        NativeFieldInternalizationPlan fieldPlan = fieldPlan();

        NativeImplementationPlan reconciled = reconciler.reconcile(
                implementationPlan("NATIVE_EMBEDDED_CLASS_BLOB_FALLBACK"),
                fieldPlan);
        NativeMethodImplementation implementation =
                reconciled.implementationFor(METHOD_KEY).orElseThrow();
        FallbackSidecarFieldAccess expected =
                FallbackSidecarFieldAccess.forMethod(fieldPlan, METHOD_KEY)
                        .get(0);

        assertTrue(implementation.fieldKeys().contains(
                expected.nativeSlotMarker()));
        assertTrue(implementation.fieldKeys().contains(expected.marker()));
        assertEquals(
                List.of(expected),
                FallbackSidecarFieldAccess.parseMarkers(
                        implementation.fieldKeys()));
    }

    @Test
    void leavesOrdinaryTemplateWithoutFallbackSidecarMetadata() {
        NativeImplementationPlan reconciled = reconciler.reconcile(
                implementationPlan("TEMPLATE_JNI_SEMANTICS"),
                fieldPlan());

        assertTrue(reconciled
                .implementationFor(METHOD_KEY)
                .orElseThrow()
                .fieldKeys()
                .isEmpty());
    }

    private NativeFieldInternalizationPlan fieldPlan() {
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
                        Optional.of(SLOT),
                        List.of(access),
                        List.of(
                                FieldInternalizationReason
                                        .FIELD_INTERNALIZATION_ELIGIBLE))));
    }

    private NativeImplementationPlan implementationPlan(String reasonCode) {
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
                new MethodNode(
                        ASM9,
                        ACC_PRIVATE | ACC_STATIC,
                        "access",
                        "()V",
                        null,
                        null));
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
        NativeMethodImplementation implementation =
                new NativeMethodImplementation(
                        entry,
                        decision,
                        NativeImplementationPath.TEMPLATE_JNI_PATH,
                        Optional.empty(),
                        reasonCode,
                        false,
                        false,
                        List.of(),
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
        return new NativeImplementationPlan(List.of(implementation));
    }
}
