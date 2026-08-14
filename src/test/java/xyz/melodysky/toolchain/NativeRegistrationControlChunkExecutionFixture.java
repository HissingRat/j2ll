package xyz.melodysky.toolchain;

import java.util.stream.IntStream;

final class NativeRegistrationControlChunkExecutionFixture {
    static final int OWNER_COUNT = 11;

    String harness(
            String registration,
            int lastInFirstChunk,
            int firstInSecondChunk,
            int firstInThirdChunk) {
        String nativeFunctions = IntStream.range(0, OWNER_COUNT)
                .mapToObj(index -> "static void native_fixture_"
                        + index
                        + "(JNIEnv* env, jclass owner) {\n"
                        + "    (void)env;\n"
                        + "    (void)owner;\n"
                        + "}\n")
                .collect(java.util.stream.Collectors.joining());
        return """
                #include <jni.h>
                #include <stdint.h>
                #include <stdlib.h>
                #include <string.h>

                enum { owner_count = %d };
                static int resolve_count = 0;
                static int register_count = 0;
                static int failure_at = 0;
                static int unregister_count = 0;
                static int owner_delete_count[owner_count];
                static uintptr_t unregister_order[owner_count];
                static int partial_binding_count[owner_count];
                static int throw_count = 0;
                static int exception_delete_count = 0;
                static int fatal_count = 0;
                static int resolver_close_count = 0;
                static jthrowable pending_exception = NULL;
                static const uintptr_t owner_base = (uintptr_t)0x1000u;
                static const uintptr_t failure_id = (uintptr_t)0x9000u;

                typedef struct {
                    jclass loader_anchor;
                    jclass class_class;
                    jobject defining_loader;
                    jmethodID class_for_name;
                } j2ll_registration_resolver;

                static jint j2ll_registration_resolver_open(
                        JNIEnv* env,
                        const char* loader_internal_name,
                        j2ll_registration_resolver* resolver) {
                    (void)env;
                    (void)loader_internal_name;
                    memset(resolver, 0, sizeof(*resolver));
                    return JNI_OK;
                }

                static void j2ll_registration_resolver_close(
                        JNIEnv* env,
                        j2ll_registration_resolver* resolver) {
                    (void)env;
                    (void)resolver;
                    resolver_close_count++;
                }

                static jclass j2ll_class_for_registration(
                        JNIEnv* env,
                        const j2ll_registration_resolver* resolver,
                        char* binary_name) {
                    (void)env;
                    (void)resolver;
                    (void)binary_name;
                    resolve_count++;
                    return (jclass)(owner_base + (uintptr_t)resolve_count);
                }

                %s

                static jint JNICALL fake_register_natives(
                        JNIEnv* env,
                        jclass owner,
                        const JNINativeMethod* methods,
                        jint count) {
                    size_t owner_index =
                            (size_t)((uintptr_t)owner - owner_base - 1u);
                    (void)env;
                    (void)methods;
                    (void)count;
                    register_count++;
                    partial_binding_count[owner_index] = 1;
                    if (register_count == failure_at) {
                        pending_exception =
                                (jthrowable)(uintptr_t)failure_id;
                        return JNI_ERR;
                    }
                    return JNI_OK;
                }

                static jint JNICALL fake_unregister_natives(
                        JNIEnv* env, jclass owner) {
                    size_t owner_index =
                            (size_t)((uintptr_t)owner - owner_base - 1u);
                    (void)env;
                    unregister_order[unregister_count++] =
                            (uintptr_t)owner;
                    partial_binding_count[owner_index] = 0;
                    return JNI_OK;
                }

                static jboolean JNICALL fake_exception_check(JNIEnv* env) {
                    (void)env;
                    return pending_exception == NULL
                            ? JNI_FALSE
                            : JNI_TRUE;
                }

                static jthrowable JNICALL fake_exception_occurred(JNIEnv* env) {
                    (void)env;
                    return pending_exception;
                }

                static void JNICALL fake_exception_clear(JNIEnv* env) {
                    (void)env;
                    pending_exception = NULL;
                }

                static jint JNICALL fake_throw(
                        JNIEnv* env, jthrowable exception) {
                    (void)env;
                    throw_count++;
                    pending_exception = exception;
                    return JNI_OK;
                }

                static void JNICALL fake_delete_local_ref(
                        JNIEnv* env, jobject reference) {
                    uintptr_t value = (uintptr_t)reference;
                    (void)env;
                    if (value > owner_base
                            && value <= owner_base + owner_count) {
                        owner_delete_count[value - owner_base - 1u]++;
                    } else if (value == failure_id) {
                        exception_delete_count++;
                    }
                }

                static void JNICALL fake_fatal_error(
                        JNIEnv* env, const char* message) {
                    (void)env;
                    (void)message;
                    fatal_count++;
                }

                static jint JNICALL fake_ensure_local_capacity(
                        JNIEnv* env, jint capacity) {
                    (void)env;
                    return capacity >= 0 ? JNI_OK : JNI_ERR;
                }

                static struct JNINativeInterface_ fake_env_table = {
                    .ExceptionOccurred = fake_exception_occurred,
                    .ExceptionClear = fake_exception_clear,
                    .DeleteLocalRef = fake_delete_local_ref,
                    .Throw = fake_throw,
                    .FatalError = fake_fatal_error,
                    .RegisterNatives = fake_register_natives,
                    .UnregisterNatives = fake_unregister_natives,
                    .ExceptionCheck = fake_exception_check,
                    .EnsureLocalCapacity = fake_ensure_local_capacity,
                };
                static JNIEnv fake_env = &fake_env_table;

                static jint JNICALL fake_get_env(
                        JavaVM* vm, void** result, jint version) {
                    (void)vm;
                    if (version != JNI_VERSION_1_8) {
                        return JNI_EVERSION;
                    }
                    *result = (void*)&fake_env;
                    return JNI_OK;
                }

                static struct JNIInvokeInterface_ fake_vm_table = {
                    .GetEnv = fake_get_env,
                };
                static JavaVM fake_vm = &fake_vm_table;

                %s

                static void reset_case(int requested_failure) {
                    resolve_count = 0;
                    register_count = 0;
                    unregister_count = 0;
                    throw_count = 0;
                    exception_delete_count = 0;
                    fatal_count = 0;
                    resolver_close_count = 0;
                    pending_exception = NULL;
                    failure_at = requested_failure;
                    memset(owner_delete_count, 0, sizeof(owner_delete_count));
                    memset(unregister_order, 0, sizeof(unregister_order));
                    memset(
                            partial_binding_count,
                            0,
                            sizeof(partial_binding_count));
                }

                static int run_success(void) {
                    reset_case(0);
                    if (JNI_OnLoad(&fake_vm, NULL) != JNI_VERSION_1_8) {
                        return 1;
                    }
                    if (resolve_count != owner_count
                            || register_count != owner_count
                            || unregister_count != 0) {
                        return 2;
                    }
                    for (int index = 0; index < owner_count; index++) {
                        if (owner_delete_count[index] != 1
                                || partial_binding_count[index] != 1) {
                            return 3;
                        }
                    }
                    if (throw_count != 0
                            || exception_delete_count != 0
                            || pending_exception != NULL
                            || fatal_count != 0
                            || resolver_close_count != 1) {
                        return 4;
                    }
                    return 0;
                }

                static int run_case(int requested_failure) {
                    reset_case(requested_failure);
                    if (JNI_OnLoad(&fake_vm, NULL) != JNI_ERR) {
                        return 1;
                    }
                    if (resolve_count != requested_failure
                            || register_count != requested_failure) {
                        return 2;
                    }
                    if (unregister_count != requested_failure) {
                        return 3;
                    }
                    for (int index = 0;
                            index < requested_failure;
                            index++) {
                        uintptr_t expected = owner_base
                                + (uintptr_t)(requested_failure - index);
                        if (unregister_order[index] != expected) {
                            return 4;
                        }
                    }
                    for (int index = 0; index < owner_count; index++) {
                        int reached = index < requested_failure ? 1 : 0;
                        if (owner_delete_count[index] != reached
                                || partial_binding_count[index] != 0) {
                            return 5;
                        }
                    }
                    if (throw_count != 2
                            || exception_delete_count != 2
                            || (uintptr_t)pending_exception != failure_id) {
                        return 6;
                    }
                    if (fatal_count != 0 || resolver_close_count != 1) {
                        return 7;
                    }
                    return 0;
                }

                int main(void) {
                    int success = run_success();
                    if (success != 0) {
                        return success;
                    }
                    const int failures[] = {%d, %d, %d};
                    for (size_t index = 0;
                            index < sizeof(failures) / sizeof(failures[0]);
                            index++) {
                        int result = run_case(failures[index]);
                        if (result != 0) {
                            return (int)(index + 1u) * 10 + result + 4;
                        }
                    }
                    return 0;
                }
                """.formatted(
                OWNER_COUNT,
                nativeFunctions,
                registration,
                lastInFirstChunk,
                firstInSecondChunk,
                firstInThirdChunk);
    }
}
