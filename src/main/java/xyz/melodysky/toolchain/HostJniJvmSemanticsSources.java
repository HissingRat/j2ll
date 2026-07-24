package xyz.melodysky.toolchain;

final class HostJniJvmSemanticsSources {
    private HostJniJvmSemanticsSources() {}

    static String classInitHelperSource() {
        return """
                jclass j2ll_rt_class_object(JNIEnv* env, int64_t class_token) {
                    const char* class_name = j2ll_find_class_object_name(class_token);
                    if (class_name == NULL) {
                        j2ll_throw_new(env, "java/lang/NoClassDefFoundError", "unknown j2ll class-init token");
                        return NULL;
                    }
                    return (*env)->FindClass(env, class_name);
                }

                void j2ll_rt_class_init_guard(JNIEnv* env, jclass class_object) {
                    (void)env;
                    (void)class_object;
                }

                void j2ll_rt_class_init_begin(JNIEnv* env, jclass class_object) {
                    (void)env;
                    (void)class_object;
                }

                void j2ll_rt_class_init_end(JNIEnv* env, jclass class_object) {
                    (void)env;
                    (void)class_object;
                }

                void j2ll_rt_class_init_failed(JNIEnv* env, jclass class_object, jthrowable throwable) {
                    (void)env;
                    (void)class_object;
                    (void)throwable;
                }

                """;
    }

    static String exceptionHelperSource() {
        return """
                void j2ll_rt_throw(JNIEnv* env, jobject throwable) {
                    if (throwable == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "throwable is null");
                        return;
                    }
                    (*env)->Throw(env, (jthrowable)throwable);
                }

                """;
    }

    static String arithmeticExceptionHelperSource() {
        return """
                int32_t j2ll_rt_div_i32(JNIEnv* env, int32_t left, int32_t right) {
                    if (right == 0) {
                        j2ll_throw_new(env, "java/lang/ArithmeticException", "/ by zero");
                        return 0;
                    }
                    if (left == INT32_MIN && right == -1) {
                        return left;
                    }
                    return left / right;
                }

                int32_t j2ll_rt_rem_i32(JNIEnv* env, int32_t left, int32_t right) {
                    if (right == 0) {
                        j2ll_throw_new(env, "java/lang/ArithmeticException", "/ by zero");
                        return 0;
                    }
                    if (left == INT32_MIN && right == -1) {
                        return 0;
                    }
                    return left % right;
                }

                int64_t j2ll_rt_div_i64(JNIEnv* env, int64_t left, int64_t right) {
                    if (right == 0) {
                        j2ll_throw_new(env, "java/lang/ArithmeticException", "/ by zero");
                        return 0;
                    }
                    if (left == INT64_MIN && right == -1) {
                        return left;
                    }
                    return left / right;
                }

                int64_t j2ll_rt_rem_i64(JNIEnv* env, int64_t left, int64_t right) {
                    if (right == 0) {
                        j2ll_throw_new(env, "java/lang/ArithmeticException", "/ by zero");
                        return 0;
                    }
                    if (left == INT64_MIN && right == -1) {
                        return 0;
                    }
                    return left % right;
                }

                """;
    }

    static String jvmNumericHelperSource() {
        return """
                int32_t j2ll_rt_i2b(int32_t value) {
                    return (int32_t)(int8_t)value;
                }

                int32_t j2ll_rt_i2c(int32_t value) {
                    return (int32_t)(uint16_t)value;
                }

                int32_t j2ll_rt_i2s(int32_t value) {
                    return (int32_t)(int16_t)value;
                }

                int32_t j2ll_rt_f2i(float value) {
                    if (isnan(value)) {
                        return 0;
                    }
                    if (value >= (float)INT32_MAX) {
                        return INT32_MAX;
                    }
                    if (value <= (float)INT32_MIN) {
                        return INT32_MIN;
                    }
                    return (int32_t)value;
                }

                int64_t j2ll_rt_f2l(float value) {
                    if (isnan(value)) {
                        return 0;
                    }
                    if (value >= (float)INT64_MAX) {
                        return INT64_MAX;
                    }
                    if (value <= (float)INT64_MIN) {
                        return INT64_MIN;
                    }
                    return (int64_t)value;
                }

                int32_t j2ll_rt_d2i(double value) {
                    if (isnan(value)) {
                        return 0;
                    }
                    if (value >= (double)INT32_MAX) {
                        return INT32_MAX;
                    }
                    if (value <= (double)INT32_MIN) {
                        return INT32_MIN;
                    }
                    return (int32_t)value;
                }

                int64_t j2ll_rt_d2l(double value) {
                    if (isnan(value)) {
                        return 0;
                    }
                    if (value >= (double)INT64_MAX) {
                        return INT64_MAX;
                    }
                    if (value <= (double)INT64_MIN) {
                        return INT64_MIN;
                    }
                    return (int64_t)value;
                }

                int32_t j2ll_rt_lcmp(int64_t left, int64_t right) {
                    return left < right ? -1 : (left > right ? 1 : 0);
                }

                int32_t j2ll_rt_fcmpl(float left, float right) {
                    if (isnan(left) || isnan(right)) {
                        return -1;
                    }
                    return left < right ? -1 : (left > right ? 1 : 0);
                }

                int32_t j2ll_rt_fcmpg(float left, float right) {
                    if (isnan(left) || isnan(right)) {
                        return 1;
                    }
                    return left < right ? -1 : (left > right ? 1 : 0);
                }

                int32_t j2ll_rt_dcmpl(double left, double right) {
                    if (isnan(left) || isnan(right)) {
                        return -1;
                    }
                    return left < right ? -1 : (left > right ? 1 : 0);
                }

                int32_t j2ll_rt_dcmpg(double left, double right) {
                    if (isnan(left) || isnan(right)) {
                        return 1;
                    }
                    return left < right ? -1 : (left > right ? 1 : 0);
                }

                """;
    }

    static String mathHelperSource() {
        return """
                int32_t j2ll_rt_math_abs_i32(int32_t value) {
                    if (value == INT32_MIN) {
                        return value;
                    }
                    return value < 0 ? -value : value;
                }

                int64_t j2ll_rt_math_abs_i64(int64_t value) {
                    if (value == INT64_MIN) {
                        return value;
                    }
                    return value < 0 ? -value : value;
                }

                float j2ll_rt_math_abs_f32(float value) {
                    return value < 0.0f ? -value : value;
                }

                double j2ll_rt_math_abs_f64(double value) {
                    return value < 0.0 ? -value : value;
                }

                int32_t j2ll_rt_math_min_i32(int32_t left, int32_t right) {
                    return left <= right ? left : right;
                }

                int64_t j2ll_rt_math_min_i64(int64_t left, int64_t right) {
                    return left <= right ? left : right;
                }

                float j2ll_rt_math_min_f32(float left, float right) {
                    return left <= right ? left : right;
                }

                double j2ll_rt_math_min_f64(double left, double right) {
                    return left <= right ? left : right;
                }

                int32_t j2ll_rt_math_max_i32(int32_t left, int32_t right) {
                    return left >= right ? left : right;
                }

                int64_t j2ll_rt_math_max_i64(int64_t left, int64_t right) {
                    return left >= right ? left : right;
                }

                float j2ll_rt_math_max_f32(float left, float right) {
                    return left >= right ? left : right;
                }

                double j2ll_rt_math_max_f64(double left, double right) {
                    return left >= right ? left : right;
                }

                """;
    }

    static String monitorHelperSource() {
        return """
                void j2ll_rt_monitor_enter(JNIEnv* env, jobject monitor) {
                    if (monitor == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "monitor is null");
                        return;
                    }
                    if ((*env)->MonitorEnter(env, monitor) != JNI_OK && !(*env)->ExceptionCheck(env)) {
                        j2ll_throw_new(env, "java/lang/IllegalMonitorStateException", "MonitorEnter failed");
                    }
                }

                void j2ll_rt_monitor_exit(JNIEnv* env, jobject monitor) {
                    if (monitor == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "monitor is null");
                        return;
                    }
                    if ((*env)->MonitorExit(env, monitor) != JNI_OK && !(*env)->ExceptionCheck(env)) {
                        j2ll_throw_new(env, "java/lang/IllegalMonitorStateException", "MonitorExit failed");
                    }
                }

                void j2ll_rt_monitor_exit_on_exception(JNIEnv* env, jobject monitor) {
                    if (monitor == NULL) {
                        if (!(*env)->ExceptionCheck(env)) {
                            j2ll_throw_new(env, "java/lang/NullPointerException", "monitor is null");
                        }
                        return;
                    }
                    if ((*env)->MonitorExit(env, monitor) != JNI_OK && !(*env)->ExceptionCheck(env)) {
                        j2ll_throw_new(env, "java/lang/IllegalMonitorStateException", "MonitorExit failed while unwinding");
                    }
                }

                """;
    }

}
