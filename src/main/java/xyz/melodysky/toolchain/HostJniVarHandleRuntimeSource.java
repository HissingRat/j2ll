package xyz.melodysky.toolchain;

final class HostJniVarHandleRuntimeSource {
    private HostJniVarHandleRuntimeSource() {}

    static String varHandleHelperSource() {
        return """
                static jobject j2ll_box_int(JNIEnv* env, int32_t value) {
                    jclass cls = (*env)->FindClass(env, "java/lang/Integer");
                    if (cls == NULL) {
                        return NULL;
                    }
                    jmethodID value_of = (*env)->GetStaticMethodID(env, cls, "valueOf", "(I)Ljava/lang/Integer;");
                    if (value_of == NULL) {
                        (*env)->DeleteLocalRef(env, cls);
                        return NULL;
                    }
                    jobject boxed = (*env)->CallStaticObjectMethod(env, cls, value_of, (jint)value);
                    (*env)->DeleteLocalRef(env, cls);
                    return boxed;
                }

                static int32_t j2ll_unbox_int(JNIEnv* env, jobject value) {
                    if (value == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "VarHandle int result is null");
                        return 0;
                    }
                    jclass cls = (*env)->FindClass(env, "java/lang/Integer");
                    if (cls == NULL) {
                        return 0;
                    }
                    jmethodID int_value = (*env)->GetMethodID(env, cls, "intValue", "()I");
                    (*env)->DeleteLocalRef(env, cls);
                    if (int_value == NULL) {
                        return 0;
                    }
                    return (int32_t)(*env)->CallIntMethod(env, value, int_value);
                }

                static int32_t j2ll_unbox_boolean(JNIEnv* env, jobject value) {
                    if (value == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "VarHandle boolean result is null");
                        return 0;
                    }
                    jclass cls = (*env)->FindClass(env, "java/lang/Boolean");
                    if (cls == NULL) {
                        return 0;
                    }
                    jmethodID boolean_value = (*env)->GetMethodID(env, cls, "booleanValue", "()Z");
                    (*env)->DeleteLocalRef(env, cls);
                    if (boolean_value == NULL) {
                        return 0;
                    }
                    return (*env)->CallBooleanMethod(env, value, boolean_value) == JNI_TRUE ? 1 : 0;
                }

                static jobjectArray j2ll_var_handle_args(JNIEnv* env, jobject target, int extra_count) {
                    jclass object_class = (*env)->FindClass(env, "java/lang/Object");
                    if (object_class == NULL) {
                        return NULL;
                    }
                    jobjectArray args = (*env)->NewObjectArray(env, 1 + extra_count, object_class, NULL);
                    (*env)->DeleteLocalRef(env, object_class);
                    if (args == NULL) {
                        return NULL;
                    }
                    (*env)->SetObjectArrayElement(env, args, 0, target);
                    return args;
                }

                static jobject j2ll_var_handle_access_mode(JNIEnv* env, const char* name) {
                    jclass cls = (*env)->FindClass(env, "java/lang/invoke/VarHandle$AccessMode");
                    if (cls == NULL) {
                        return NULL;
                    }
                    jfieldID field = (*env)->GetStaticFieldID(env, cls, name, "Ljava/lang/invoke/VarHandle$AccessMode;");
                    if (field == NULL) {
                        (*env)->DeleteLocalRef(env, cls);
                        return NULL;
                    }
                    jobject mode = (*env)->GetStaticObjectField(env, cls, field);
                    (*env)->DeleteLocalRef(env, cls);
                    return mode;
                }

                static jobject j2ll_var_handle_method_handle(JNIEnv* env, jobject handle, const char* mode_name) {
                    jobject mode = j2ll_var_handle_access_mode(env, mode_name);
                    if (mode == NULL) {
                        return NULL;
                    }
                    jclass cls = (*env)->FindClass(env, "java/lang/invoke/VarHandle");
                    if (cls == NULL) {
                        (*env)->DeleteLocalRef(env, mode);
                        return NULL;
                    }
                    jmethodID method = (*env)->GetMethodID(
                            env,
                            cls,
                            "toMethodHandle",
                            "(Ljava/lang/invoke/VarHandle$AccessMode;)Ljava/lang/invoke/MethodHandle;");
                    (*env)->DeleteLocalRef(env, cls);
                    if (method == NULL) {
                        (*env)->DeleteLocalRef(env, mode);
                        return NULL;
                    }
                    jobject method_handle = (*env)->CallObjectMethod(env, handle, method, mode);
                    (*env)->DeleteLocalRef(env, mode);
                    return method_handle;
                }

                static jobject j2ll_invoke_method_handle_with_args(JNIEnv* env, jobject method_handle, jobjectArray args) {
                    if (method_handle == NULL) {
                        return NULL;
                    }
                    jclass cls = (*env)->FindClass(env, "java/lang/invoke/MethodHandle");
                    if (cls == NULL) {
                        return NULL;
                    }
                    jmethodID invoke = (*env)->GetMethodID(env, cls, "invokeWithArguments", "([Ljava/lang/Object;)Ljava/lang/Object;");
                    (*env)->DeleteLocalRef(env, cls);
                    if (invoke == NULL) {
                        return NULL;
                    }
                    return (*env)->CallObjectMethod(env, method_handle, invoke, args);
                }

                static int32_t j2ll_var_handle_get_int(JNIEnv* env, jobject handle, jobject target, const char* mode_name) {
                    if (handle == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "VarHandle receiver is null");
                        return 0;
                    }
                    jobjectArray args = j2ll_var_handle_args(env, target, 0);
                    if (args == NULL) {
                        return 0;
                    }
                    jobject method_handle = j2ll_var_handle_method_handle(env, handle, mode_name);
                    if (method_handle == NULL) {
                        (*env)->DeleteLocalRef(env, args);
                        return 0;
                    }
                    jobject boxed = j2ll_invoke_method_handle_with_args(env, method_handle, args);
                    (*env)->DeleteLocalRef(env, method_handle);
                    (*env)->DeleteLocalRef(env, args);
                    if ((*env)->ExceptionCheck(env)) {
                        return 0;
                    }
                    int32_t result = j2ll_unbox_int(env, boxed);
                    if (boxed != NULL) {
                        (*env)->DeleteLocalRef(env, boxed);
                    }
                    return result;
                }

                static void j2ll_var_handle_set_int(JNIEnv* env, jobject handle, jobject target, int32_t value, const char* mode_name) {
                    if (handle == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "VarHandle receiver is null");
                        return;
                    }
                    jobjectArray args = j2ll_var_handle_args(env, target, 1);
                    if (args == NULL) {
                        return;
                    }
                    jobject boxed = j2ll_box_int(env, value);
                    if (boxed == NULL) {
                        (*env)->DeleteLocalRef(env, args);
                        return;
                    }
                    (*env)->SetObjectArrayElement(env, args, 1, boxed);
                    (*env)->DeleteLocalRef(env, boxed);
                    jobject method_handle = j2ll_var_handle_method_handle(env, handle, mode_name);
                    if (method_handle == NULL) {
                        (*env)->DeleteLocalRef(env, args);
                        return;
                    }
                    jobject ignored = j2ll_invoke_method_handle_with_args(env, method_handle, args);
                    if (ignored != NULL) {
                        (*env)->DeleteLocalRef(env, ignored);
                    }
                    (*env)->DeleteLocalRef(env, method_handle);
                    (*env)->DeleteLocalRef(env, args);
                }

                int32_t j2ll_rt_var_handle_get_int(JNIEnv* env, jobject handle, jobject target) {
                    return j2ll_var_handle_get_int(env, handle, target, "GET");
                }

                void j2ll_rt_var_handle_set_int(JNIEnv* env, jobject handle, jobject target, int32_t value) {
                    j2ll_var_handle_set_int(env, handle, target, value, "SET");
                }

                int32_t j2ll_rt_var_handle_get_volatile_int(JNIEnv* env, jobject handle, jobject target) {
                    return j2ll_var_handle_get_int(env, handle, target, "GET_VOLATILE");
                }

                void j2ll_rt_var_handle_set_volatile_int(JNIEnv* env, jobject handle, jobject target, int32_t value) {
                    j2ll_var_handle_set_int(env, handle, target, value, "SET_VOLATILE");
                }

                int32_t j2ll_rt_var_handle_compare_and_set_int(JNIEnv* env, jobject handle, jobject target, int32_t expected, int32_t update) {
                    if (handle == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "VarHandle receiver is null");
                        return 0;
                    }
                    jobjectArray args = j2ll_var_handle_args(env, target, 2);
                    if (args == NULL) {
                        return 0;
                    }
                    jobject boxed_expected = j2ll_box_int(env, expected);
                    jobject boxed_update = j2ll_box_int(env, update);
                    if (boxed_expected == NULL || boxed_update == NULL) {
                        (*env)->DeleteLocalRef(env, args);
                        return 0;
                    }
                    (*env)->SetObjectArrayElement(env, args, 1, boxed_expected);
                    (*env)->SetObjectArrayElement(env, args, 2, boxed_update);
                    (*env)->DeleteLocalRef(env, boxed_expected);
                    (*env)->DeleteLocalRef(env, boxed_update);
                    jobject method_handle = j2ll_var_handle_method_handle(env, handle, "COMPARE_AND_SET");
                    if (method_handle == NULL) {
                        (*env)->DeleteLocalRef(env, args);
                        return 0;
                    }
                    jobject boxed = j2ll_invoke_method_handle_with_args(env, method_handle, args);
                    (*env)->DeleteLocalRef(env, method_handle);
                    (*env)->DeleteLocalRef(env, args);
                    if ((*env)->ExceptionCheck(env)) {
                        return 0;
                    }
                    int32_t success = j2ll_unbox_boolean(env, boxed);
                    if (boxed != NULL) {
                        (*env)->DeleteLocalRef(env, boxed);
                    }
                    return success;
                }

                """;
    }

}
