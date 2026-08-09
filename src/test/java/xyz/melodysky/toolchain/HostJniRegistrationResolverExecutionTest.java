package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

final class HostJniRegistrationResolverExecutionTest {
    @TempDir
    Path temp;

    @Test
    void nonNullResultWithPendingExceptionStopsEveryResolverStageAndBalancesRefs()
            throws Exception {
        Path clang = findClang().orElse(null);
        assumeTrue(clang != null, "clang is required for the fake-JNI resolver test");
        assumeTrue(
                Files.isRegularFile(
                        Path.of(System.getProperty("java.home")).resolve("include/jni.h")),
                "JDK JNI headers are required for the fake-JNI resolver test");

        String harness = """
                #include <jni.h>
                #include <stdint.h>
                #include <string.h>

                enum {
                    FIND_LOADER = 1,
                    GET_CLASS_CLASS = 2,
                    GET_CLASS_LOADER_METHOD = 3,
                    CALL_GET_CLASS_LOADER = 4,
                    GET_CLASS_FOR_NAME_METHOD = 5,
                    NEW_BINARY_NAME = 6,
                    CALL_CLASS_FOR_NAME = 7,
                    REGISTER_OWNER = 8
                };

                enum {
                    LOADER_REF = 0,
                    CLASS_CLASS_REF = 1,
                    DEFINING_LOADER_REF = 2,
                    NAME_REF = 3,
                    OWNER_REF = 4,
                    REF_COUNT = 5
                };

                static const uintptr_t refs[REF_COUNT] = {
                    UINT64_C(0x101),
                    UINT64_C(0x102),
                    UINT64_C(0x103),
                    UINT64_C(0x104),
                    UINT64_C(0x105)
                };
                static const uintptr_t pending_id = UINT64_C(0x999);
                static const uintptr_t get_loader_method_id = UINT64_C(0x201);
                static const uintptr_t for_name_method_id = UINT64_C(0x202);

                static int failure_stage = 0;
                static int calls[REGISTER_OWNER + 1] = {0};
                static int deletes[REF_COUNT] = {0};
                static jthrowable pending_exception = NULL;

                static void fail_at(int stage) {
                    if (failure_stage == stage) {
                        pending_exception =
                                (jthrowable)(uintptr_t)pending_id;
                    }
                }

                static jclass JNICALL fake_find_class(
                        JNIEnv* env, const char* name) {
                    (void)env;
                    (void)name;
                    calls[FIND_LOADER]++;
                    fail_at(FIND_LOADER);
                    return (jclass)(uintptr_t)refs[LOADER_REF];
                }

                static jclass JNICALL fake_get_object_class(
                        JNIEnv* env, jobject object) {
                    (void)env;
                    (void)object;
                    calls[GET_CLASS_CLASS]++;
                    fail_at(GET_CLASS_CLASS);
                    return (jclass)(uintptr_t)refs[CLASS_CLASS_REF];
                }

                static jmethodID JNICALL fake_get_method_id(
                        JNIEnv* env,
                        jclass owner,
                        const char* name,
                        const char* descriptor) {
                    (void)env;
                    (void)owner;
                    (void)name;
                    (void)descriptor;
                    calls[GET_CLASS_LOADER_METHOD]++;
                    fail_at(GET_CLASS_LOADER_METHOD);
                    return (jmethodID)(uintptr_t)get_loader_method_id;
                }

                static jobject JNICALL fake_call_object_method(
                        JNIEnv* env, jobject receiver, jmethodID method, ...) {
                    (void)env;
                    (void)receiver;
                    (void)method;
                    calls[CALL_GET_CLASS_LOADER]++;
                    fail_at(CALL_GET_CLASS_LOADER);
                    return (jobject)(uintptr_t)refs[DEFINING_LOADER_REF];
                }

                static jmethodID JNICALL fake_get_static_method_id(
                        JNIEnv* env,
                        jclass owner,
                        const char* name,
                        const char* descriptor) {
                    (void)env;
                    (void)owner;
                    (void)name;
                    (void)descriptor;
                    calls[GET_CLASS_FOR_NAME_METHOD]++;
                    fail_at(GET_CLASS_FOR_NAME_METHOD);
                    return (jmethodID)(uintptr_t)for_name_method_id;
                }

                static jstring JNICALL fake_new_string_utf(
                        JNIEnv* env, const char* value) {
                    (void)env;
                    (void)value;
                    calls[NEW_BINARY_NAME]++;
                    fail_at(NEW_BINARY_NAME);
                    return (jstring)(uintptr_t)refs[NAME_REF];
                }

                static jobject JNICALL fake_call_static_object_method_a(
                        JNIEnv* env,
                        jclass owner,
                        jmethodID method,
                        const jvalue* arguments) {
                    (void)env;
                    (void)owner;
                    (void)method;
                    (void)arguments;
                    calls[CALL_CLASS_FOR_NAME]++;
                    fail_at(CALL_CLASS_FOR_NAME);
                    return (jobject)(uintptr_t)refs[OWNER_REF];
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
                    calls[REGISTER_OWNER]++;
                    return JNI_OK;
                }

                static jboolean JNICALL fake_exception_check(JNIEnv* env) {
                    (void)env;
                    return pending_exception == NULL
                            ? JNI_FALSE
                            : JNI_TRUE;
                }

                static void JNICALL fake_delete_local_ref(
                        JNIEnv* env, jobject reference) {
                    (void)env;
                    uintptr_t value = (uintptr_t)reference;
                    for (int index = 0; index < REF_COUNT; index++) {
                        if (value == refs[index]) {
                            deletes[index]++;
                            return;
                        }
                    }
                }

                static struct JNINativeInterface_ fake_env_table = {
                    .FindClass = fake_find_class,
                    .GetObjectClass = fake_get_object_class,
                    .GetMethodID = fake_get_method_id,
                    .CallObjectMethod = fake_call_object_method,
                    .GetStaticMethodID = fake_get_static_method_id,
                    .NewStringUTF = fake_new_string_utf,
                    .CallStaticObjectMethodA = fake_call_static_object_method_a,
                    .RegisterNatives = fake_register_natives,
                    .ExceptionCheck = fake_exception_check,
                    .DeleteLocalRef = fake_delete_local_ref,
                };
                static JNIEnv fake_env = &fake_env_table;

                """
                + HostJniRegistrationRuntimeSource.helperSource()
                + """

                static int run_case(int stage) {
                    failure_stage = stage;
                    memset(calls, 0, sizeof(calls));
                    memset(deletes, 0, sizeof(deletes));
                    pending_exception = NULL;

                    j2ll_registration_resolver resolver = {
                        NULL, NULL, NULL, NULL
                    };
                    jint open_status = j2ll_registration_resolver_open(
                            &fake_env,
                            "registration/fixture/Loader",
                            &resolver);
                    if (stage <= GET_CLASS_FOR_NAME_METHOD) {
                        if (open_status != JNI_ERR) {
                            return 10 + stage;
                        }
                    } else {
                        if (open_status != JNI_OK) {
                            return 20 + stage;
                        }
                        char owner_name[] = "registration/fixture/Owner";
                        jclass owner = j2ll_class_for_registration(
                                &fake_env,
                                &resolver,
                                owner_name);
                        if (owner != NULL) {
                            fake_register_natives(
                                    &fake_env,
                                    owner,
                                    NULL,
                                    0);
                        }
                    }
                    j2ll_registration_resolver_close(
                            &fake_env,
                            &resolver);

                    for (int call = FIND_LOADER;
                            call <= CALL_CLASS_FOR_NAME;
                            call++) {
                        int expected = call <= stage ? 1 : 0;
                        if (calls[call] != expected) {
                            return 30 + stage;
                        }
                    }
                    if (calls[REGISTER_OWNER] != 0) {
                        return 40 + stage;
                    }
                    int expected_deletes[REF_COUNT] = {
                        1,
                        stage >= GET_CLASS_CLASS ? 1 : 0,
                        stage >= CALL_GET_CLASS_LOADER ? 1 : 0,
                        stage >= NEW_BINARY_NAME ? 1 : 0,
                        stage >= CALL_CLASS_FOR_NAME ? 1 : 0
                    };
                    for (int index = 0; index < REF_COUNT; index++) {
                        if (deletes[index] != expected_deletes[index]) {
                            return 50 + stage;
                        }
                    }
                    if ((uintptr_t)pending_exception != pending_id) {
                        return 60 + stage;
                    }
                    if (resolver.loader_anchor != NULL
                            || resolver.class_class != NULL
                            || resolver.defining_loader != NULL
                            || resolver.class_for_name != NULL) {
                        return 70 + stage;
                    }
                    return 0;
                }

                int main(void) {
                    for (int stage = FIND_LOADER;
                            stage <= CALL_CLASS_FOR_NAME;
                            stage++) {
                        int result = run_case(stage);
                        if (result != 0) {
                            return result;
                        }
                    }
                    return 0;
                }
                """;

        Path include = new ZigJniHeaderSet()
                .prepare(ZigBuildWorkspace.under(temp))
                .get(0);
        Path source = temp.resolve("registration_resolver_pending.c");
        Path executable = temp.resolve(isWindows()
                ? "registration_resolver_pending.exe"
                : "registration_resolver_pending");
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
        assertTrue(run.waitFor(15, TimeUnit.SECONDS), "fake-JNI resolver harness timed out");
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
