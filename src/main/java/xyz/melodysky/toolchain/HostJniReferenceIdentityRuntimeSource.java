package xyz.melodysky.toolchain;

/** Emits the narrow JNI helper used for Java reference identity checks. */
final class HostJniReferenceIdentityRuntimeSource {
    private HostJniReferenceIdentityRuntimeSource() {}

    static String helperSource() {
        return """
                int32_t j2ll_rt_is_same_object(JNIEnv* env, jobject left, jobject right) {
                    return (*env)->IsSameObject(env, left, right) == JNI_TRUE ? 1 : 0;
                }

                """;
    }
}
