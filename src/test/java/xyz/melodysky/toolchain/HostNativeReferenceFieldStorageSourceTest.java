package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import xyz.melodysky.analysis.field.FieldId;
import xyz.melodysky.analysis.field.NativeFieldStorageKind;
import xyz.melodysky.ir.model.NativeFieldSlotRef;
import xyz.melodysky.packaging.FallbackSidecarFieldAccess;
import xyz.melodysky.packaging.RuntimeLoaderPlan;

class HostNativeReferenceFieldStorageSourceTest {
    @Test
    void emitsJvmObjectArrayBridgeWithoutNativeStrongReferences() {
        String rawFieldIdentity = "pkg/State#distinctiveReference!Ljava/lang/Object;";
        var bindings = List.of(binding(List.of(referenceSlot(), rawFieldIdentity)));
        StringBuilder source = new StringBuilder();

        HostNativeReferenceFieldStorageSource.append(
                source,
                bindings,
                RuntimeLoaderPlan.create("app/native", false, 1));
        String generated = source.toString();

        assertEquals(1, HostNativeReferenceFieldStorageSource.requiredSidecarSize(bindings));
        assertTrue(generated.contains("FindClass(env, \"app/native/Loader\")"));
        assertTrue(generated.contains("\"referenceSidecar\""));
        assertTrue(generated.contains("CallStaticObjectMethod"));
        assertTrue(generated.contains("j2ll_nfs_reference_sidecar_cached"));
        assertTrue(generated.contains("j2ll_nfs_release_reference_sidecar"));
        assertTrue(generated.contains("GetObjectArrayElement"));
        assertTrue(generated.contains("SetObjectArrayElement"));
        assertTrue(generated.contains("DeleteLocalRef(env, *cache_slot)"));
        assertTrue(generated.contains("ExceptionCheck"));
        assertFalse(generated.contains("NewGlobalRef"));
        assertFalse(generated.contains("NewWeakGlobalRef"));
        assertFalse(generated.contains(rawFieldIdentity));
        assertFalse(generated.contains("distinctiveReference"));
    }

    @Test
    void rejectsLoaderPlanSmallerThanReferenceLayout() {
        var bindings = List.of(binding(List.of(referenceSlot())));

        assertThrows(
                IllegalArgumentException.class,
                () -> HostNativeReferenceFieldStorageSource.append(
                        new StringBuilder(),
                        bindings,
                        RuntimeLoaderPlan.create("native0", false, 0)));
    }

    @Test
    void fallbackOnlyReferenceSlotEmitsSidecarBridgeWithoutPlanningMarkerLeak() {
        String rawFieldIdentity =
                "pkg/FallbackState#distinctiveFallbackReference!Ljava/lang/Object;";
        FallbackSidecarFieldAccess access = new FallbackSidecarFieldAccess(
                new FieldId(
                        "pkg/FallbackState",
                        "distinctiveFallbackReference",
                        "Ljava/lang/Object;"),
                new NativeFieldSlotRef(
                        NativeFieldStorageKind.REFERENCE,
                        "j2ll_nfs_fallback_only",
                        0),
                1,
                1);
        var bindings = List.of(binding(
                NativeImplementationPath.TEMPLATE_JNI_PATH,
                List.of(access.nativeSlotMarker(), access.marker())));
        StringBuilder source = new StringBuilder();

        HostNativeReferenceFieldStorageSource.append(
                source,
                bindings,
                RuntimeLoaderPlan.create("app/native", true, 1));
        String generated = source.toString();

        assertEquals(
                1,
                HostNativeReferenceFieldStorageSource.requiredSidecarSize(
                        bindings));
        assertTrue(generated.contains("j2ll_nfs_reference_sidecar"));
        assertTrue(generated.contains("GetObjectArrayElement"));
        assertTrue(generated.contains("SetObjectArrayElement"));
        assertFalse(generated.contains(access.marker()));
        assertFalse(generated.contains("fallback-sidecar:v1:"));
        assertFalse(generated.contains(rawFieldIdentity));
        assertFalse(generated.contains("distinctiveFallbackReference"));
    }

    @Test
    void primitiveStorageIsNotEmittedForReferenceOnlyPlan() {
        StringBuilder source = new StringBuilder("prefix");

        HostNativeFieldStorageSource.append(
                source,
                List.of(binding(List.of(referenceSlot()))));

        assertEquals("prefix", source.toString());
    }

    private String referenceSlot() {
        return "native-slot:" + new NativeFieldSlotRef(
                        NativeFieldStorageKind.REFERENCE,
                        "j2ll_nfs_0011223344556677",
                        0)
                .encoded();
    }

    private HostJniCSourceGenerator.Binding binding(List<String> fieldKeys) {
        return binding(NativeImplementationPath.LLVM_NATIVE_PATH, fieldKeys);
    }

    private HostJniCSourceGenerator.Binding binding(
            NativeImplementationPath path,
            List<String> fieldKeys) {
        return new HostJniCSourceGenerator.Binding(
                null,
                null,
                path,
                Optional.empty(),
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
                Optional.empty(),
                "test",
                null);
    }
}
