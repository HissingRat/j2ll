package xyz.melodysky.toolchain;

final class HostJniRegistrationRuntimeSource {
    private HostJniRegistrationRuntimeSource() {}

    static String helperSource() {
        return """
                static void j2ll_throw_new(JNIEnv* env, const char* class_name, const char* message) {
                    jclass exception_class = (*env)->FindClass(env, class_name);
                    if (exception_class == NULL) {
                        return;
                    }
                    (*env)->ThrowNew(env, exception_class, message);
                    (*env)->DeleteLocalRef(env, exception_class);
                }

                static jclass j2ll_class_for_registration(
                        JNIEnv* env, const char* internal_name) {
                    return (*env)->FindClass(env, internal_name);
                }

                """;
    }
}
