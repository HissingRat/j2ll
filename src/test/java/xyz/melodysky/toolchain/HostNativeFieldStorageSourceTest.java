package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import xyz.melodysky.analysis.field.NativeFieldStorageKind;
import xyz.melodysky.ir.model.NativeFieldSlotRef;

class HostNativeFieldStorageSourceTest {
    @Test
    void emitsWeakClassKeyedAtomicStorageOnlyForInternalizedFieldBindings() {
        String rawFieldIdentity = "pkg/State#counter!I";
        StringBuilder source = new StringBuilder();

        HostNativeFieldStorageSource.append(
                source,
                List.of(binding(
                        NativeImplementationPath.LLVM_NATIVE_PATH,
                        List.of(nativeSlot(NativeFieldStorageKind.INT), rawFieldIdentity))));
        String generated = source.toString();

        assertTrue(generated.contains("jweak defining_class"));
        assertTrue(generated.contains("IsSameObject"));
        assertTrue(generated.contains("DeleteWeakGlobalRef"));
        assertTrue(generated.contains("NewWeakGlobalRef"));
        assertTrue(generated.contains("_Atomic uint64_t value"));
        assertTrue(generated.contains("memory_order_relaxed"));
        assertTrue(generated.contains("atomic_flag_test_and_set_explicit"));
        assertTrue(generated.contains("j2ll_nfs_get_i32"));
        assertTrue(generated.contains("j2ll_nfs_put_i32"));
        assertTrue(generated.contains("j2ll_nfs_get_i64"));
        assertTrue(generated.contains("j2ll_nfs_put_i64"));
        assertTrue(generated.contains("j2ll_nfs_get_z"));
        assertTrue(generated.contains("j2ll_nfs_put_b"));
        assertTrue(generated.contains("j2ll_nfs_get_s"));
        assertTrue(generated.contains("j2ll_nfs_get_c"));
        assertTrue(generated.contains("j2ll_nfs_get_f32_bits"));
        assertTrue(generated.contains("j2ll_nfs_put_f64_bits"));
        assertTrue(generated.contains("(uint32_t)value & UINT32_C(1)"));
        assertTrue(generated.contains("UINT64_C(0)"));
        assertFalse(generated.contains(rawFieldIdentity));
        assertFalse(generated.contains("pkg/State"));
        assertFalse(generated.contains("counter"));
    }

    @Test
    void doesNotEmitStorageForOrdinaryOrFallbackFieldBindings() {
        StringBuilder ordinary = new StringBuilder("prefix");
        HostNativeFieldStorageSource.append(
                ordinary,
                List.of(binding(
                        NativeImplementationPath.LLVM_NATIVE_PATH,
                        List.of("pkg/State#counter!I"))));
        assertEquals("prefix", ordinary.toString());

        StringBuilder fallback = new StringBuilder("prefix");
        HostNativeFieldStorageSource.append(
                fallback,
                List.of(binding(
                        NativeImplementationPath.TEMPLATE_JNI_PATH,
                        List.of(nativeSlot(NativeFieldStorageKind.INT)))));
        assertEquals("prefix", fallback.toString());
    }

    private String nativeSlot(NativeFieldStorageKind kind) {
        return "native-slot:" + new NativeFieldSlotRef(
                        kind,
                        "j2ll_nfs_0011223344556677",
                        kind.reference() ? 0 : -1)
                .encoded();
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
