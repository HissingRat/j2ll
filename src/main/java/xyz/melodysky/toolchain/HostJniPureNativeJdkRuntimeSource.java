package xyz.melodysky.toolchain;

/** Small JNI implementations for call combinations proven not to expose their JDK intermediates. */
final class HostJniPureNativeJdkRuntimeSource {
    private HostJniPureNativeJdkRuntimeSource() {
    }

    static String helperSource() {
        return """
                jarray j2ll_rt_i32_be_frame_new(JNIEnv* env) {
                    return (*env)->NewByteArray(env, 4);
                }

                jarray j2ll_rt_i32_be_frame_write(JNIEnv* env, jarray frame, int32_t value) {
                    uint32_t bits = (uint32_t)value;
                    jbyte encoded[4];
                    encoded[0] = (jbyte)((bits >> 24) & UINT32_C(0xff));
                    encoded[1] = (jbyte)((bits >> 16) & UINT32_C(0xff));
                    encoded[2] = (jbyte)((bits >> 8) & UINT32_C(0xff));
                    encoded[3] = (jbyte)(bits & UINT32_C(0xff));
                    (*env)->SetByteArrayRegion(env, (jbyteArray)frame, 0, 4, encoded);
                    if ((*env)->ExceptionCheck(env)) {
                        return NULL;
                    }
                    return frame;
                }

                jarray j2ll_rt_i32_be_frame_finish(JNIEnv* env, jarray frame) {
                    (void)env;
                    return frame;
                }

                """;
    }
}
