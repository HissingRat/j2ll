package xyz.melodysky.toolchain;

final class HostJniTypeAndStringRuntimeSources {
    private HostJniTypeAndStringRuntimeSources() {}

    static String typeHelperSource() {
        return "";
    }

    static String stringHelperSource() {
        return """
                int32_t j2ll_rt_string_length(JNIEnv* env, jobject value) {
                    if (value == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "string receiver is null");
                        return 0;
                    }
                    return (*env)->GetStringLength(env, (jstring)value);
                }

                int32_t j2ll_rt_string_is_empty(JNIEnv* env, jobject value) {
                    if (value == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "string receiver is null");
                        return 0;
                    }
                    return (*env)->GetStringLength(env, (jstring)value) == 0 ? 1 : 0;
                }

                int32_t j2ll_rt_string_char_at(JNIEnv* env, jobject value, int32_t index) {
                    if (value == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "string receiver is null");
                        return 0;
                    }
                    jclass cls = (*env)->FindClass(env, "java/lang/String");
                    if (cls == NULL) {
                        return 0;
                    }
                    jmethodID method = (*env)->GetMethodID(env, cls, "charAt", "(I)C");
                    (*env)->DeleteLocalRef(env, cls);
                    if (method == NULL) {
                        return 0;
                    }
                    return (int32_t)(*env)->CallCharMethod(env, value, method, (jint)index);
                }

                int32_t j2ll_rt_string_equals(JNIEnv* env, jobject receiver, jobject other) {
                    if (receiver == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "string receiver is null");
                        return 0;
                    }
                    if (other == NULL) {
                        return 0;
                    }
                    jclass cls = (*env)->FindClass(env, "java/lang/String");
                    if (cls == NULL) {
                        return 0;
                    }
                    jmethodID equals = (*env)->GetMethodID(env, cls, "equals", "(Ljava/lang/Object;)Z");
                    (*env)->DeleteLocalRef(env, cls);
                    if (equals == NULL) {
                        return 0;
                    }
                    return (*env)->CallBooleanMethod(env, receiver, equals, other) == JNI_TRUE ? 1 : 0;
                }

                static int32_t j2ll_call_string_boolean_method(JNIEnv* env, jobject receiver, jobject argument, const char* name) {
                    if (receiver == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "string receiver is null");
                        return 0;
                    }
                    jclass cls = (*env)->FindClass(env, "java/lang/String");
                    if (cls == NULL) {
                        return 0;
                    }
                    jmethodID method = (*env)->GetMethodID(env, cls, name, "(Ljava/lang/String;)Z");
                    (*env)->DeleteLocalRef(env, cls);
                    if (method == NULL) {
                        return 0;
                    }
                    return (*env)->CallBooleanMethod(env, receiver, method, argument) == JNI_TRUE ? 1 : 0;
                }

                int32_t j2ll_rt_string_starts_with(JNIEnv* env, jobject receiver, jobject prefix) {
                    return j2ll_call_string_boolean_method(env, receiver, prefix, "startsWith");
                }

                int32_t j2ll_rt_string_ends_with(JNIEnv* env, jobject receiver, jobject suffix) {
                    return j2ll_call_string_boolean_method(env, receiver, suffix, "endsWith");
                }

                jobject j2ll_rt_string_substring(JNIEnv* env, jobject receiver, int32_t begin_index) {
                    if (receiver == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "string receiver is null");
                        return NULL;
                    }
                    jclass cls = (*env)->FindClass(env, "java/lang/String");
                    if (cls == NULL) {
                        return NULL;
                    }
                    jmethodID method = (*env)->GetMethodID(env, cls, "substring", "(I)Ljava/lang/String;");
                    (*env)->DeleteLocalRef(env, cls);
                    if (method == NULL) {
                        return NULL;
                    }
                    return (*env)->CallObjectMethod(env, receiver, method, (jint)begin_index);
                }

                jobject j2ll_rt_string_substring_range(JNIEnv* env, jobject receiver, int32_t begin_index, int32_t end_index) {
                    if (receiver == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "string receiver is null");
                        return NULL;
                    }
                    jclass cls = (*env)->FindClass(env, "java/lang/String");
                    if (cls == NULL) {
                        return NULL;
                    }
                    jmethodID method = (*env)->GetMethodID(env, cls, "substring", "(II)Ljava/lang/String;");
                    (*env)->DeleteLocalRef(env, cls);
                    if (method == NULL) {
                        return NULL;
                    }
                    return (*env)->CallObjectMethod(env, receiver, method, (jint)begin_index, (jint)end_index);
                }

                static jclass j2ll_string_builder_class(JNIEnv* env) {
                    return (*env)->FindClass(env, "java/lang/StringBuilder");
                }

                jobject j2ll_rt_string_builder_new(JNIEnv* env) {
                    jclass cls = j2ll_string_builder_class(env);
                    if (cls == NULL) {
                        return NULL;
                    }
                    jmethodID init = (*env)->GetMethodID(env, cls, "<init>", "()V");
                    if (init == NULL) {
                        (*env)->DeleteLocalRef(env, cls);
                        return NULL;
                    }
                    jobject builder = (*env)->NewObject(env, cls, init);
                    (*env)->DeleteLocalRef(env, cls);
                    return builder;
                }

                void j2ll_rt_string_builder_init(JNIEnv* env, jobject builder) {
                    if (builder == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "StringBuilder receiver is null");
                        return;
                    }
                    jclass cls = j2ll_string_builder_class(env);
                    if (cls == NULL) {
                        return;
                    }
                    jmethodID init = (*env)->GetMethodID(env, cls, "<init>", "()V");
                    if (init == NULL) {
                        (*env)->DeleteLocalRef(env, cls);
                        return;
                    }
                    (*env)->CallNonvirtualVoidMethod(env, builder, cls, init);
                    (*env)->DeleteLocalRef(env, cls);
                }

                static jobject j2ll_call_string_builder_append(JNIEnv* env, jobject builder, const char* descriptor, ...) {
                    if (builder == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "StringBuilder receiver is null");
                        return NULL;
                    }
                    jclass cls = j2ll_string_builder_class(env);
                    if (cls == NULL) {
                        return NULL;
                    }
                    jmethodID method = (*env)->GetMethodID(env, cls, "append", descriptor);
                    (*env)->DeleteLocalRef(env, cls);
                    if (method == NULL) {
                        return NULL;
                    }
                    va_list args;
                    va_start(args, descriptor);
                    jobject result = (*env)->CallObjectMethodV(env, builder, method, args);
                    va_end(args);
                    return result;
                }

                jobject j2ll_rt_string_builder_append_ref(JNIEnv* env, jobject builder, jobject value) {
                    return j2ll_call_string_builder_append(env, builder, "(Ljava/lang/Object;)Ljava/lang/StringBuilder;", value);
                }

                jobject j2ll_rt_string_builder_append_i32(JNIEnv* env, jobject builder, int32_t value) {
                    return j2ll_call_string_builder_append(env, builder, "(I)Ljava/lang/StringBuilder;", (jint)value);
                }

                jobject j2ll_rt_string_builder_append_i64(JNIEnv* env, jobject builder, int64_t value) {
                    return j2ll_call_string_builder_append(env, builder, "(J)Ljava/lang/StringBuilder;", (jlong)value);
                }

                jobject j2ll_rt_string_builder_append_f32(JNIEnv* env, jobject builder, float value) {
                    return j2ll_call_string_builder_append(env, builder, "(F)Ljava/lang/StringBuilder;", (jfloat)value);
                }

                jobject j2ll_rt_string_builder_append_f64(JNIEnv* env, jobject builder, double value) {
                    return j2ll_call_string_builder_append(env, builder, "(D)Ljava/lang/StringBuilder;", (jdouble)value);
                }

                jobject j2ll_rt_string_builder_to_string(JNIEnv* env, jobject builder) {
                    if (builder == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "StringBuilder receiver is null");
                        return NULL;
                    }
                    jclass cls = j2ll_string_builder_class(env);
                    if (cls == NULL) {
                        return NULL;
                    }
                    jmethodID method = (*env)->GetMethodID(env, cls, "toString", "()Ljava/lang/String;");
                    (*env)->DeleteLocalRef(env, cls);
                    if (method == NULL) {
                        return NULL;
                    }
                    return (*env)->CallObjectMethod(env, builder, method);
                }

                void j2ll_rt_system_arraycopy(JNIEnv* env, jobject src, int32_t src_pos, jobject dst, int32_t dst_pos, int32_t length) {
                    jclass system_class = (*env)->FindClass(env, "java/lang/System");
                    if (system_class == NULL) {
                        return;
                    }
                    jmethodID method = (*env)->GetStaticMethodID(
                            env,
                            system_class,
                            "arraycopy",
                            "(Ljava/lang/Object;ILjava/lang/Object;II)V");
                    if (method == NULL) {
                        (*env)->DeleteLocalRef(env, system_class);
                        return;
                    }
                    (*env)->CallStaticVoidMethod(env, system_class, method, src, (jint)src_pos, dst, (jint)dst_pos, (jint)length);
                    (*env)->DeleteLocalRef(env, system_class);
                }

                """;
    }

}
