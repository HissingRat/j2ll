package xyz.melodysky.toolchain;

import java.util.List;
import xyz.melodysky.ir.model.NativeFieldSlotRef;

/**
 * ClassLoader-isolated storage for plan-approved static primitive fields.
 */
final class HostNativeFieldStorageSource {
    private HostNativeFieldStorageSource() {
    }

    static void append(
            StringBuilder builder,
            List<HostJniCSourceGenerator.Binding> bindings) {
        boolean needed = bindings.stream()
                .filter(binding -> binding.path() == NativeImplementationPath.LLVM_NATIVE_PATH)
                .flatMap(binding -> binding.fieldKeys().stream())
                .filter(fieldKey -> fieldKey.startsWith("native-slot:"))
                .map(fieldKey -> fieldKey.substring("native-slot:".length()))
                .map(NativeFieldSlotRef::parse)
                .flatMap(java.util.Optional::stream)
                .anyMatch(slot -> !slot.kind().reference());
        if (!needed) {
            return;
        }
        builder.append("""
                typedef struct j2ll_native_field_state {
                    jweak defining_class;
                    int64_t slot_token;
                    _Atomic uint64_t value;
                    struct j2ll_native_field_state* next;
                } j2ll_native_field_state;

                static j2ll_native_field_state* j2ll_native_field_states = NULL;
                static atomic_flag j2ll_native_field_lock = ATOMIC_FLAG_INIT;

                static void j2ll_native_field_lock_acquire(void) {
                    while (atomic_flag_test_and_set_explicit(
                            &j2ll_native_field_lock, memory_order_acquire)) {
                    }
                }

                static void j2ll_native_field_lock_release(void) {
                    atomic_flag_clear_explicit(&j2ll_native_field_lock, memory_order_release);
                }

                static j2ll_native_field_state* j2ll_native_field_state_for(
                        JNIEnv* env,
                        jclass defining_class,
                        int64_t slot_token) {
                    if (defining_class == NULL) {
                        j2ll_throw_new(env, "java/lang/IllegalStateException",
                                "native field storage requires defining class");
                        return NULL;
                    }
                    j2ll_native_field_lock_acquire();
                    j2ll_native_field_state** cursor = &j2ll_native_field_states;
                    while (*cursor != NULL) {
                        j2ll_native_field_state* candidate = *cursor;
                        if ((*env)->IsSameObject(env, candidate->defining_class, NULL)) {
                            *cursor = candidate->next;
                            (*env)->DeleteWeakGlobalRef(env, candidate->defining_class);
                            free(candidate);
                            continue;
                        }
                        if (candidate->slot_token == slot_token
                                && (*env)->IsSameObject(
                                        env, candidate->defining_class, defining_class)) {
                            j2ll_native_field_lock_release();
                            return candidate;
                        }
                        cursor = &candidate->next;
                    }
                    j2ll_native_field_state* created =
                            (j2ll_native_field_state*)calloc(1, sizeof(j2ll_native_field_state));
                    if (created == NULL) {
                        j2ll_native_field_lock_release();
                        j2ll_throw_new(env, "java/lang/OutOfMemoryError",
                                "native field state allocation failed");
                        return NULL;
                    }
                    created->defining_class = (*env)->NewWeakGlobalRef(env, defining_class);
                    if (created->defining_class == NULL) {
                        free(created);
                        j2ll_native_field_lock_release();
                        return NULL;
                    }
                    created->slot_token = slot_token;
                    atomic_init(&created->value, UINT64_C(0));
                    created->next = j2ll_native_field_states;
                    j2ll_native_field_states = created;
                    j2ll_native_field_lock_release();
                    return created;
                }

                int32_t j2ll_nfs_get_z(
                        JNIEnv* env, jclass defining_class, int64_t slot_token) {
                    j2ll_native_field_state* state =
                            j2ll_native_field_state_for(env, defining_class, slot_token);
                    return state == NULL
                            ? 0
                            : atomic_load_explicit(
                                    &state->value, memory_order_relaxed) != UINT64_C(0);
                }

                void j2ll_nfs_put_z(
                        JNIEnv* env, jclass defining_class, int64_t slot_token, int32_t value) {
                    j2ll_native_field_state* state =
                            j2ll_native_field_state_for(env, defining_class, slot_token);
                    if (state != NULL) {
                        atomic_store_explicit(
                                &state->value, (uint64_t)((uint32_t)value & UINT32_C(1)),
                                memory_order_relaxed);
                    }
                }

                int32_t j2ll_nfs_get_b(
                        JNIEnv* env, jclass defining_class, int64_t slot_token) {
                    j2ll_native_field_state* state =
                            j2ll_native_field_state_for(env, defining_class, slot_token);
                    if (state == NULL) {
                        return 0;
                    }
                    uint32_t raw = (uint32_t)atomic_load_explicit(
                            &state->value, memory_order_relaxed) & UINT32_C(0xff);
                    return raw < UINT32_C(0x80)
                            ? (int32_t)raw
                            : (int32_t)(raw - UINT32_C(0x100));
                }

                void j2ll_nfs_put_b(
                        JNIEnv* env, jclass defining_class, int64_t slot_token, int32_t value) {
                    j2ll_native_field_state* state =
                            j2ll_native_field_state_for(env, defining_class, slot_token);
                    if (state != NULL) {
                        atomic_store_explicit(
                                &state->value, (uint64_t)(uint8_t)value, memory_order_relaxed);
                    }
                }

                int32_t j2ll_nfs_get_s(
                        JNIEnv* env, jclass defining_class, int64_t slot_token) {
                    j2ll_native_field_state* state =
                            j2ll_native_field_state_for(env, defining_class, slot_token);
                    if (state == NULL) {
                        return 0;
                    }
                    uint32_t raw = (uint32_t)atomic_load_explicit(
                            &state->value, memory_order_relaxed) & UINT32_C(0xffff);
                    return raw < UINT32_C(0x8000)
                            ? (int32_t)raw
                            : (int32_t)(raw - UINT32_C(0x10000));
                }

                void j2ll_nfs_put_s(
                        JNIEnv* env, jclass defining_class, int64_t slot_token, int32_t value) {
                    j2ll_native_field_state* state =
                            j2ll_native_field_state_for(env, defining_class, slot_token);
                    if (state != NULL) {
                        atomic_store_explicit(
                                &state->value, (uint64_t)(uint16_t)value, memory_order_relaxed);
                    }
                }

                int32_t j2ll_nfs_get_c(
                        JNIEnv* env, jclass defining_class, int64_t slot_token) {
                    j2ll_native_field_state* state =
                            j2ll_native_field_state_for(env, defining_class, slot_token);
                    return state == NULL
                            ? 0
                            : (int32_t)(uint16_t)atomic_load_explicit(
                                    &state->value, memory_order_relaxed);
                }

                void j2ll_nfs_put_c(
                        JNIEnv* env, jclass defining_class, int64_t slot_token, int32_t value) {
                    j2ll_native_field_state* state =
                            j2ll_native_field_state_for(env, defining_class, slot_token);
                    if (state != NULL) {
                        atomic_store_explicit(
                                &state->value, (uint64_t)(uint16_t)value, memory_order_relaxed);
                    }
                }

                int32_t j2ll_nfs_get_i32(
                        JNIEnv* env, jclass defining_class, int64_t slot_token) {
                    j2ll_native_field_state* state =
                            j2ll_native_field_state_for(env, defining_class, slot_token);
                    if (state == NULL) {
                        return 0;
                    }
                    uint32_t bits = (uint32_t)atomic_load_explicit(
                            &state->value, memory_order_relaxed);
                    int32_t value = 0;
                    memcpy(&value, &bits, sizeof(value));
                    return value;
                }

                void j2ll_nfs_put_i32(
                        JNIEnv* env, jclass defining_class, int64_t slot_token, int32_t value) {
                    j2ll_native_field_state* state =
                            j2ll_native_field_state_for(env, defining_class, slot_token);
                    if (state != NULL) {
                        atomic_store_explicit(
                                &state->value, (uint64_t)(uint32_t)value, memory_order_relaxed);
                    }
                }

                int64_t j2ll_nfs_get_i64(
                        JNIEnv* env, jclass defining_class, int64_t slot_token) {
                    j2ll_native_field_state* state =
                            j2ll_native_field_state_for(env, defining_class, slot_token);
                    if (state == NULL) {
                        return 0;
                    }
                    uint64_t bits = atomic_load_explicit(
                            &state->value, memory_order_relaxed);
                    int64_t value = 0;
                    memcpy(&value, &bits, sizeof(value));
                    return value;
                }

                void j2ll_nfs_put_i64(
                        JNIEnv* env, jclass defining_class, int64_t slot_token, int64_t value) {
                    j2ll_native_field_state* state =
                            j2ll_native_field_state_for(env, defining_class, slot_token);
                    if (state != NULL) {
                        atomic_store_explicit(
                                &state->value, (uint64_t)value, memory_order_relaxed);
                    }
                }

                int32_t j2ll_nfs_get_f32_bits(
                        JNIEnv* env, jclass defining_class, int64_t slot_token) {
                    return j2ll_nfs_get_i32(env, defining_class, slot_token);
                }

                void j2ll_nfs_put_f32_bits(
                        JNIEnv* env, jclass defining_class, int64_t slot_token, int32_t bits) {
                    j2ll_nfs_put_i32(env, defining_class, slot_token, bits);
                }

                int64_t j2ll_nfs_get_f64_bits(
                        JNIEnv* env, jclass defining_class, int64_t slot_token) {
                    return j2ll_nfs_get_i64(env, defining_class, slot_token);
                }

                void j2ll_nfs_put_f64_bits(
                        JNIEnv* env, jclass defining_class, int64_t slot_token, int64_t bits) {
                    j2ll_nfs_put_i64(env, defining_class, slot_token, bits);
                }

                """);
    }
}
