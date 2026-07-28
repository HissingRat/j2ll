package xyz.melodysky.toolchain;

final class HostJniJdkObjectRuntimeSource {
    private HostJniJdkObjectRuntimeSource() {}

    static String jdkObjectHelperSource() {
        return """
                jobject j2ll_rt_object_get_class(JNIEnv* env, jobject value) {
                    if (value == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "Object.getClass receiver is null");
                        return NULL;
                    }
                    return (*env)->GetObjectClass(env, value);
                }

                jobject j2ll_rt_class_get_class_loader(JNIEnv* env, jobject value) {
                    if (value == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "Class.getClassLoader receiver is null");
                        return NULL;
                    }
                    jclass class_class = (*env)->GetObjectClass(env, value);
                    if (class_class == NULL) {
                        return NULL;
                    }
                    jmethodID method = (*env)->GetMethodID(
                            env,
                            class_class,
                            "getClassLoader",
                            "()Ljava/lang/ClassLoader;");
                    (*env)->DeleteLocalRef(env, class_class);
                    if (method == NULL) {
                        return NULL;
                    }
                    return (*env)->CallObjectMethod(env, value, method);
                }

                static jobject j2ll_call_static_box(JNIEnv* env, const char* class_name, const char* descriptor, ...) {
                    jclass cls = (*env)->FindClass(env, class_name);
                    if (cls == NULL) {
                        return NULL;
                    }
                    jmethodID method = (*env)->GetStaticMethodID(env, cls, "valueOf", descriptor);
                    if (method == NULL) {
                        (*env)->DeleteLocalRef(env, cls);
                        return NULL;
                    }
                    va_list args;
                    va_start(args, descriptor);
                    jobject result = (*env)->CallStaticObjectMethodV(env, cls, method, args);
                    va_end(args);
                    (*env)->DeleteLocalRef(env, cls);
                    return result;
                }

                jobject j2ll_rt_integer_value_of(JNIEnv* env, int32_t value) {
                    return j2ll_call_static_box(env, "java/lang/Integer", "(I)Ljava/lang/Integer;", (jint)value);
                }

                int32_t j2ll_rt_integer_int_value(JNIEnv* env, jobject value) {
                    if (value == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "Integer receiver is null");
                        return 0;
                    }
                    jclass cls = (*env)->FindClass(env, "java/lang/Integer");
                    if (cls == NULL) {
                        return 0;
                    }
                    jmethodID method = (*env)->GetMethodID(env, cls, "intValue", "()I");
                    (*env)->DeleteLocalRef(env, cls);
                    if (method == NULL) {
                        return 0;
                    }
                    return (*env)->CallIntMethod(env, value, method);
                }

                jobject j2ll_rt_long_value_of(JNIEnv* env, int64_t value) {
                    return j2ll_call_static_box(env, "java/lang/Long", "(J)Ljava/lang/Long;", (jlong)value);
                }

                int64_t j2ll_rt_long_long_value(JNIEnv* env, jobject value) {
                    if (value == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "Long receiver is null");
                        return 0;
                    }
                    jclass cls = (*env)->FindClass(env, "java/lang/Long");
                    if (cls == NULL) {
                        return 0;
                    }
                    jmethodID method = (*env)->GetMethodID(env, cls, "longValue", "()J");
                    (*env)->DeleteLocalRef(env, cls);
                    if (method == NULL) {
                        return 0;
                    }
                    return (*env)->CallLongMethod(env, value, method);
                }

                jobject j2ll_rt_boolean_value_of(JNIEnv* env, int32_t value) {
                    return j2ll_call_static_box(env, "java/lang/Boolean", "(Z)Ljava/lang/Boolean;", value != 0 ? JNI_TRUE : JNI_FALSE);
                }

                int32_t j2ll_rt_boolean_boolean_value(JNIEnv* env, jobject value) {
                    if (value == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "Boolean receiver is null");
                        return 0;
                    }
                    jclass cls = (*env)->FindClass(env, "java/lang/Boolean");
                    if (cls == NULL) {
                        return 0;
                    }
                    jmethodID method = (*env)->GetMethodID(env, cls, "booleanValue", "()Z");
                    (*env)->DeleteLocalRef(env, cls);
                    if (method == NULL) {
                        return 0;
                    }
                    return (*env)->CallBooleanMethod(env, value, method) == JNI_TRUE ? 1 : 0;
                }

                jobject j2ll_rt_double_value_of(JNIEnv* env, double value) {
                    return j2ll_call_static_box(env, "java/lang/Double", "(D)Ljava/lang/Double;", (jdouble)value);
                }

                double j2ll_rt_double_double_value(JNIEnv* env, jobject value) {
                    if (value == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "Double receiver is null");
                        return 0.0;
                    }
                    jclass cls = (*env)->FindClass(env, "java/lang/Double");
                    if (cls == NULL) {
                        return 0.0;
                    }
                    jmethodID method = (*env)->GetMethodID(env, cls, "doubleValue", "()D");
                    (*env)->DeleteLocalRef(env, cls);
                    if (method == NULL) {
                        return 0.0;
                    }
                    return (*env)->CallDoubleMethod(env, value, method);
                }

                jobject j2ll_rt_objects_require_non_null(JNIEnv* env, jobject value) {
                    if (value == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "required object is null");
                    }
                    return value;
                }

                int32_t j2ll_rt_objects_equals(JNIEnv* env, jobject left, jobject right) {
                    if ((*env)->IsSameObject(env, left, right)) {
                        return 1;
                    }
                    if (left == NULL || right == NULL) {
                        return 0;
                    }
                    jclass cls = (*env)->GetObjectClass(env, left);
                    if (cls == NULL) {
                        return 0;
                    }
                    jmethodID method = (*env)->GetMethodID(env, cls, "equals", "(Ljava/lang/Object;)Z");
                    (*env)->DeleteLocalRef(env, cls);
                    if (method == NULL) {
                        return 0;
                    }
                    return (*env)->CallBooleanMethod(env, left, method, right) == JNI_TRUE ? 1 : 0;
                }

                """;
    }

}
