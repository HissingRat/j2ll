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

                typedef struct {
                    jclass loader_anchor;
                    jclass class_class;
                    jobject defining_loader;
                    jmethodID class_for_name;
                } j2ll_registration_resolver;

                static void j2ll_registration_resolver_close(
                        JNIEnv* env,
                        j2ll_registration_resolver* resolver) {
                    if (resolver == NULL) {
                        return;
                    }
                    if (resolver->defining_loader != NULL) {
                        (*env)->DeleteLocalRef(env, resolver->defining_loader);
                        resolver->defining_loader = NULL;
                    }
                    if (resolver->class_class != NULL) {
                        (*env)->DeleteLocalRef(env, resolver->class_class);
                        resolver->class_class = NULL;
                    }
                    if (resolver->loader_anchor != NULL) {
                        (*env)->DeleteLocalRef(env, resolver->loader_anchor);
                        resolver->loader_anchor = NULL;
                    }
                    resolver->class_for_name = NULL;
                }

                static jint j2ll_registration_resolver_open(
                        JNIEnv* env,
                        const char* loader_internal_name,
                        j2ll_registration_resolver* resolver) {
                    if (resolver == NULL || loader_internal_name == NULL) {
                        return JNI_ERR;
                    }
                    resolver->loader_anchor =
                            (*env)->FindClass(env, loader_internal_name);
                    jboolean loader_anchor_exception =
                            (*env)->ExceptionCheck(env);
                    if (resolver->loader_anchor == NULL
                            || loader_anchor_exception) {
                        if (resolver->loader_anchor != NULL) {
                            (*env)->DeleteLocalRef(
                                    env,
                                    resolver->loader_anchor);
                            resolver->loader_anchor = NULL;
                        }
                        return JNI_ERR;
                    }
                    resolver->class_class =
                            (*env)->GetObjectClass(env, resolver->loader_anchor);
                    jboolean class_class_exception =
                            (*env)->ExceptionCheck(env);
                    if (resolver->class_class == NULL
                            || class_class_exception) {
                        if (resolver->class_class != NULL) {
                            (*env)->DeleteLocalRef(
                                    env,
                                    resolver->class_class);
                            resolver->class_class = NULL;
                        }
                        return JNI_ERR;
                    }
                    jmethodID get_class_loader = (*env)->GetMethodID(
                            env,
                            resolver->class_class,
                            "getClassLoader",
                            "()Ljava/lang/ClassLoader;");
                    jboolean get_class_loader_exception =
                            (*env)->ExceptionCheck(env);
                    if (get_class_loader == NULL
                            || get_class_loader_exception) {
                        return JNI_ERR;
                    }
                    resolver->defining_loader = (*env)->CallObjectMethod(
                            env,
                            resolver->loader_anchor,
                            get_class_loader);
                    jboolean defining_loader_exception =
                            (*env)->ExceptionCheck(env);
                    if (defining_loader_exception) {
                        if (resolver->defining_loader != NULL) {
                            (*env)->DeleteLocalRef(
                                    env,
                                    resolver->defining_loader);
                            resolver->defining_loader = NULL;
                        }
                        return JNI_ERR;
                    }
                    resolver->class_for_name = (*env)->GetStaticMethodID(
                            env,
                            resolver->class_class,
                            "forName",
                            "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;");
                    jboolean class_for_name_exception =
                            (*env)->ExceptionCheck(env);
                    return resolver->class_for_name == NULL
                                    || class_for_name_exception
                            ? JNI_ERR
                            : JNI_OK;
                }

                static jclass j2ll_class_for_registration(
                        JNIEnv* env,
                        const j2ll_registration_resolver* resolver,
                        char* binary_name) {
                    if (resolver == NULL
                            || resolver->class_class == NULL
                            || resolver->class_for_name == NULL
                            || binary_name == NULL) {
                        return NULL;
                    }
                    for (char* cursor = binary_name; *cursor != '\\0'; cursor++) {
                        if (*cursor == '/') {
                            *cursor = '.';
                        }
                    }
                    jstring name = (*env)->NewStringUTF(env, binary_name);
                    jboolean name_exception = (*env)->ExceptionCheck(env);
                    if (name == NULL || name_exception) {
                        if (name != NULL) {
                            (*env)->DeleteLocalRef(env, name);
                        }
                        return NULL;
                    }
                    jvalue arguments[3] = {{0}};
                    arguments[0].l = name;
                    arguments[1].z = JNI_FALSE;
                    arguments[2].l = resolver->defining_loader;
                    jclass result = (jclass)(*env)->CallStaticObjectMethodA(
                            env,
                            resolver->class_class,
                            resolver->class_for_name,
                            arguments);
                    jboolean result_exception = (*env)->ExceptionCheck(env);
                    if (result_exception && result != NULL) {
                        (*env)->DeleteLocalRef(env, result);
                        result = NULL;
                    }
                    (*env)->DeleteLocalRef(env, name);
                    return result_exception ? NULL : result;
                }

                """;
    }
}
