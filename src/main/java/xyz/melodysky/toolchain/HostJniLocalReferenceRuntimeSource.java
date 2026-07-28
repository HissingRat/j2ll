package xyz.melodysky.toolchain;

final class HostJniLocalReferenceRuntimeSource {
    private HostJniLocalReferenceRuntimeSource() {}

    static boolean isNeeded(NativeImplementationPlan implementationPlan) {
        return implementationPlan.localReferencePlans()
                .values()
                .stream()
                .anyMatch(plan -> plan.emitsReleases());
    }

    static void appendIfNeeded(
            StringBuilder source,
            NativeImplementationPlan implementationPlan) {
        if (isNeeded(implementationPlan)) {
            source.append(helperSource());
        }
    }

    static String helperSource() {
        return """
                void j2ll_rt_release_local_ref(
                        JNIEnv* env, jobject value, int32_t owned) {
                    if (owned != 0 && value != NULL) {
                        (*env)->DeleteLocalRef(env, value);
                    }
                }

                """;
    }
}
