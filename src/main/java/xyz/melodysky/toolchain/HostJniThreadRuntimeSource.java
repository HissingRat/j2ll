package xyz.melodysky.toolchain;

final class HostJniThreadRuntimeSource {
    private HostJniThreadRuntimeSource() {}

    static String threadHelperSource() {
        return """
                void j2ll_rt_thread_sleep(JNIEnv* env, int64_t millis) {
                    jclass thread_class = (*env)->FindClass(env, "java/lang/Thread");
                    if (thread_class == NULL) {
                        return;
                    }
                    jmethodID sleep_method = (*env)->GetStaticMethodID(
                            env,
                            thread_class,
                            "sleep",
                            "(J)V");
                    if (sleep_method == NULL) {
                        (*env)->DeleteLocalRef(env, thread_class);
                        return;
                    }
                    jvalue arguments[1];
                    arguments[0].j = (jlong)millis;
                    (*env)->CallStaticVoidMethodA(
                            env,
                            thread_class,
                            sleep_method,
                            arguments);
                    (*env)->DeleteLocalRef(env, thread_class);
                }

                """;
    }
}
