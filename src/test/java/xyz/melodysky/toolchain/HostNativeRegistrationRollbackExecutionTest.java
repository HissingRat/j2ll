package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.melodysky.packaging.MethodTableHidingPlanner;
import xyz.melodysky.packaging.NativeRegistrationEntry;
import xyz.melodysky.packaging.NativeRegistrationPlan;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;

final class HostNativeRegistrationRollbackExecutionTest {
    @TempDir
    Path temp;

    @Test
    void zeroOwnerSourceCompilesAndOnLoadReturnsTheSupportedJniVersion()
            throws Exception {
        Path clang = findClang().orElse(null);
        assumeTrue(clang != null, "clang is required for the zero-owner registration test");
        assumeTrue(
                Files.isRegularFile(
                        Path.of(System.getProperty("java.home")).resolve("include/jni.h")),
                "JDK JNI headers are required for the zero-owner registration test");

        NativeRegistrationPlan plan = new NativeRegistrationPlan(List.of());
        String registration = new HostNativeRegistrationSource().emit(
                plan,
                new MethodTableHidingPlanner().plan(plan, false, 0L),
                NativeTextBuildKey.fromUtf8("zero-owner-registration"));
        assertTrue(registration.contains("return JNI_VERSION_1_8;"));
        assertFalse(registration.contains("registered_owners["));
        assertFalse(registration.contains("UnregisterNatives(env"));

        String harness = """
                #include <jni.h>
                #include <stdint.h>
                #include <stdlib.h>

                static JNIEnv fake_env = NULL;
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

                """
                + registration
                + """

                int main(void) {
                    return JNI_OnLoad(&fake_vm, NULL) == JNI_VERSION_1_8
                            ? 0
                            : 1;
                }
                """;

        Path include = new ZigJniHeaderSet()
                .prepare(ZigBuildWorkspace.under(temp.resolve("zero-owner")))
                .get(0);
        Path source = temp.resolve("zero_owner_registration.c");
        Path executable = temp.resolve(isWindows()
                ? "zero_owner_registration.exe"
                : "zero_owner_registration");
        Files.writeString(source, harness, StandardCharsets.UTF_8);

        Process compile = new ProcessBuilder(
                        clang.toString(),
                        "-std=c11",
                        "-I",
                        include.toString(),
                        source.toString(),
                        "-o",
                        executable.toString())
                .redirectErrorStream(true)
                .start();
        assertTrue(compile.waitFor(45, TimeUnit.SECONDS), "clang compile timed out");
        String compileOutput = new String(
                compile.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertEquals(0, compile.exitValue(), compileOutput);

        Process run = new ProcessBuilder(executable.toString())
                .redirectErrorStream(true)
                .start();
        assertTrue(run.waitFor(15, TimeUnit.SECONDS), "zero-owner harness timed out");
        String runOutput = new String(
                run.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertEquals(0, run.exitValue(), runOutput);
    }

    @Test
    void rollbackChecksJniStatusAndFailsClosedWithoutAnException()
            throws Exception {
        Path clang = findClang().orElse(null);
        assumeTrue(clang != null, "clang is required for the fake-JNI rollback test");
        assumeTrue(
                Files.isRegularFile(
                        Path.of(System.getProperty("java.home")).resolve("include/jni.h")),
                "JDK JNI headers are required for the fake-JNI rollback test");

        NativeRegistrationPlan plan = new NativeRegistrationPlan(List.of(
                new NativeRegistrationEntry(
                        "rollback/Alpha", "run", "()V", "j2ll_fn_alpha"),
                new NativeRegistrationEntry(
                        "rollback/Beta", "run", "()V", "j2ll_fn_beta"),
                new NativeRegistrationEntry(
                        "rollback/Gamma", "run", "()V", "j2ll_fn_gamma")));
        String registration = new HostNativeRegistrationSource().emit(
                plan,
                new MethodTableHidingPlanner().plan(plan, false, 0L),
                NativeTextBuildKey.fromUtf8("fake-jni-atomic-rollback"));
        String harness = """
                #include <jni.h>
                #include <setjmp.h>
                #include <stdint.h>
                #include <stdlib.h>
                #include <string.h>

                static int resolve_count = 0;
                static int register_count = 0;
                static int failure_at = 0;
                static int unregister_count = 0;
                static int unregister_failure_at = 0;
                static int unregister_exception_at = 0;
                static int throw_failure_at = 0;
                static uintptr_t unregister_order[3] = {0, 0, 0};
                static int owner_delete_count[3] = {0, 0, 0};
                static int partial_binding_count[3] = {0, 0, 0};
                static int throw_count = 0;
                static int fatal_count = 0;
                static int fatal_message_kind = 0;
                static int fatal_jump_enabled = 0;
                static jmp_buf fatal_jump;
                static jthrowable pending_exception = NULL;
                static const uintptr_t owner_base = UINT64_C(0x100);
                static const uintptr_t failure_id = UINT64_C(0x900);
                static const uintptr_t rollback_id = UINT64_C(0x901);

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
                }

                static jclass j2ll_class_for_registration(
                        JNIEnv* env,
                        const j2ll_registration_resolver* resolver,
                        char* binary_name) {
                    (void)env;
                    (void)resolver;
                    (void)binary_name;
                    resolve_count++;
                    return (jclass)(uintptr_t)(owner_base + (uintptr_t)resolve_count);
                }

                static void j2ll_fn_alpha(JNIEnv* env, jclass owner) {
                    (void)env;
                    (void)owner;
                }
                static void j2ll_fn_beta(JNIEnv* env, jclass owner) {
                    (void)env;
                    (void)owner;
                }
                static void j2ll_fn_gamma(JNIEnv* env, jclass owner) {
                    (void)env;
                    (void)owner;
                }

                static jint JNICALL fake_register_natives(
                        JNIEnv* env,
                        jclass owner,
                        const JNINativeMethod* methods,
                        jint count) {
                    (void)env;
                    (void)owner;
                    (void)methods;
                    (void)count;
                    register_count++;
                    if (register_count == failure_at) {
                        partial_binding_count[
                                (uintptr_t)owner - owner_base - 1] = 1;
                        pending_exception =
                                (jthrowable)(uintptr_t)failure_id;
                        return JNI_ERR;
                    }
                    return JNI_OK;
                }

                static jint JNICALL fake_unregister_natives(
                        JNIEnv* env, jclass owner) {
                    (void)env;
                    unregister_order[unregister_count++] =
                            (uintptr_t)owner;
                    if (unregister_count == unregister_exception_at) {
                        pending_exception =
                                (jthrowable)(uintptr_t)rollback_id;
                    }
                    if (unregister_count == unregister_failure_at) {
                        return JNI_ERR;
                    }
                    if (unregister_count != unregister_exception_at) {
                        partial_binding_count[
                                (uintptr_t)owner - owner_base - 1] = 0;
                    }
                    return JNI_OK;
                }

                static jboolean JNICALL fake_exception_check(JNIEnv* env) {
                    (void)env;
                    return pending_exception == NULL ? JNI_FALSE : JNI_TRUE;
                }

                static jthrowable JNICALL fake_exception_occurred(JNIEnv* env) {
                    (void)env;
                    return pending_exception;
                }

                static void JNICALL fake_exception_clear(JNIEnv* env) {
                    (void)env;
                    pending_exception = NULL;
                }

                static jint JNICALL fake_throw(JNIEnv* env, jthrowable exception) {
                    (void)env;
                    throw_count++;
                    if (throw_count == throw_failure_at) {
                        return JNI_ERR;
                    }
                    pending_exception = exception;
                    return JNI_OK;
                }

                static void JNICALL fake_delete_local_ref(
                        JNIEnv* env, jobject reference) {
                    uintptr_t value = (uintptr_t)reference;
                    (void)env;
                    if (value > owner_base && value <= owner_base + 3) {
                        owner_delete_count[value - owner_base - 1]++;
                    }
                }

                static void JNICALL fake_fatal_error(
                        JNIEnv* env, const char* message) {
                    (void)env;
                    fatal_count++;
                    if (strcmp(
                            message,
                            "native owner registration rollback failed") == 0) {
                        fatal_message_kind = 1;
                    } else if (strcmp(
                            message,
                            "native registration rollback failed") == 0) {
                        fatal_message_kind = 2;
                    } else if (strcmp(
                            message,
                            "native registration exception restore failed") == 0) {
                        fatal_message_kind = 3;
                    } else {
                        fatal_message_kind = -1;
                    }
                    if (fatal_jump_enabled) {
                        longjmp(fatal_jump, 1);
                    }
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

                """
                + registration
                + """

                static int run_case(
                        int requested_failure,
                        int requested_unregister_failure,
                        int requested_unregister_exception,
                        int requested_throw_failure) {
                    resolve_count = 0;
                    register_count = 0;
                    unregister_count = 0;
                    unregister_failure_at = requested_unregister_failure;
                    unregister_exception_at =
                            requested_unregister_exception;
                    throw_failure_at = requested_throw_failure;
                    throw_count = 0;
                    fatal_count = 0;
                    fatal_message_kind = 0;
                    fatal_jump_enabled = 0;
                    pending_exception = NULL;
                    memset(unregister_order, 0, sizeof(unregister_order));
                    memset(owner_delete_count, 0, sizeof(owner_delete_count));
                    memset(
                            partial_binding_count,
                            0,
                            sizeof(partial_binding_count));
                    failure_at = requested_failure;
                    int fatal_expected =
                            requested_unregister_failure != 0
                            || requested_unregister_exception != 0
                            || requested_throw_failure != 0;
                    jint result = JNI_ERR;
                    if (fatal_expected) {
                        fatal_jump_enabled = 1;
                        if (setjmp(fatal_jump) == 0) {
                            result = JNI_OnLoad(&fake_vm, NULL);
                            fatal_jump_enabled = 0;
                            return 9;
                        }
                        fatal_jump_enabled = 0;
                    } else {
                        result = JNI_OnLoad(&fake_vm, NULL);
                        if (result != JNI_ERR) {
                            return 10;
                        }
                    }
                    if (resolve_count != requested_failure
                            || register_count != requested_failure) {
                        return 11;
                    }
                    int first_rollback_failed =
                            requested_unregister_failure == 1
                            || requested_unregister_exception == 1;
                    int expected_unregister_count =
                            first_rollback_failed
                            ? 1
                            : requested_failure;
                    if (unregister_count != expected_unregister_count) {
                        return 12;
                    }
                    for (int index = 0; index < unregister_count; index++) {
                        uintptr_t expected =
                                owner_base
                                + (uintptr_t)(requested_failure - index);
                        if (unregister_order[index] != expected) {
                            return 13;
                        }
                    }
                    for (int index = 0; index < 3; index++) {
                        int expected = 0;
                        if (first_rollback_failed) {
                            expected =
                                    index == requested_failure - 1
                                    ? 1
                                    : 0;
                        } else {
                            expected =
                                    index < requested_failure
                                    ? 1
                                    : 0;
                        }
                        if (owner_delete_count[index] != expected) {
                            return 14;
                        }
                    }
                    if (!first_rollback_failed
                            && partial_binding_count[
                                    requested_failure - 1] != 0) {
                        return 17;
                    }
                    if (!fatal_expected) {
                        if (fatal_count != 0
                                || throw_count != 2
                                || (uintptr_t)pending_exception != failure_id) {
                            return 15;
                        }
                    } else {
                        int expected_throw_count =
                                requested_throw_failure != 0
                                ? requested_throw_failure
                                : (first_rollback_failed ? 0 : 1);
                        int expected_fatal_message_kind =
                                first_rollback_failed
                                ? 1
                                : (requested_throw_failure != 0 ? 3 : 2);
                        if (fatal_count != 1
                                || throw_count != expected_throw_count
                                || fatal_message_kind
                                        != expected_fatal_message_kind
                                || pending_exception != NULL) {
                            return 16;
                        }
                    }
                    return 0;
                }

                int main(void) {
                    int second_owner_failure = run_case(2, 0, 0, 0);
                    if (second_owner_failure != 0) {
                        return second_owner_failure;
                    }
                    int third_owner_failure = run_case(3, 0, 0, 0);
                    if (third_owner_failure != 0) {
                        return 20 + third_owner_failure;
                    }
                    int owner_rollback_status_failure =
                            run_case(3, 1, 0, 0);
                    if (owner_rollback_status_failure != 0) {
                        return 40 + owner_rollback_status_failure;
                    }
                    int owner_rollback_exception =
                            run_case(3, 0, 1, 0);
                    if (owner_rollback_exception != 0) {
                        return 60 + owner_rollback_exception;
                    }
                    int root_rollback_status_failure =
                            run_case(3, 2, 0, 0);
                    if (root_rollback_status_failure != 0) {
                        return 80 + root_rollback_status_failure;
                    }
                    int root_rollback_exception =
                            run_case(3, 0, 2, 0);
                    if (root_rollback_exception != 0) {
                        return 100 + root_rollback_exception;
                    }
                    int root_exception_restore_failure =
                            run_case(3, 0, 0, 2);
                    if (root_exception_restore_failure != 0) {
                        return 120 + root_exception_restore_failure;
                    }
                    return 0;
                }
                """;

        ZigBuildWorkspace workspace = ZigBuildWorkspace.under(temp);
        Path include = new ZigJniHeaderSet().prepare(workspace).get(0);
        Path source = temp.resolve("registration_rollback.c");
        Path executable = temp.resolve(isWindows()
                ? "registration_rollback.exe"
                : "registration_rollback");
        Files.writeString(source, harness, StandardCharsets.UTF_8);

        Process compile = new ProcessBuilder(
                        clang.toString(),
                        "-std=c11",
                        "-I",
                        include.toString(),
                        source.toString(),
                        "-o",
                        executable.toString())
                .redirectErrorStream(true)
                .start();
        assertTrue(compile.waitFor(45, TimeUnit.SECONDS), "clang compile timed out");
        String compileOutput = new String(
                compile.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertEquals(0, compile.exitValue(), compileOutput);

        Process run = new ProcessBuilder(executable.toString())
                .redirectErrorStream(true)
                .start();
        assertTrue(run.waitFor(15, TimeUnit.SECONDS), "fake-JNI harness timed out");
        String runOutput = new String(
                run.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertEquals(0, run.exitValue(), runOutput);
    }

    private Optional<Path> findClang() {
        String configured = System.getProperty("j2ll.test.clang");
        if (configured != null && !configured.isBlank()) {
            Path candidate = Path.of(configured);
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        List<String> names = isWindows()
                ? List.of("clang.exe", "clang")
                : List.of("clang");
        for (String directory : path.split(Pattern.quote(File.pathSeparator))) {
            if (directory.isBlank()) {
                continue;
            }
            for (String name : names) {
                Path candidate = Path.of(directory).resolve(name);
                if (Files.isRegularFile(candidate)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win");
    }
}
