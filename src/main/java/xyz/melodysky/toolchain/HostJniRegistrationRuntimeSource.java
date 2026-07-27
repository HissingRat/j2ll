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

                static char* j2ll_dotted_class_name(const char* internal_name) {
                    size_t length = strlen(internal_name);
                    char* dotted = (char*)malloc(length + 1);
                    if (dotted == NULL) {
                        return NULL;
                    }
                    for (size_t index = 0; index < length; index++) {
                        dotted[index] = internal_name[index] == '/' ? '.' : internal_name[index];
                    }
                    dotted[length] = '\\0';
                    return dotted;
                }

                static jobject j2ll_context_class_loader(JNIEnv* env) {
                    jclass thread_class = (*env)->FindClass(env, "java/lang/Thread");
                    if (thread_class == NULL) {
                        return NULL;
                    }
                    jmethodID current_thread = (*env)->GetStaticMethodID(
                            env,
                            thread_class,
                            "currentThread",
                            "()Ljava/lang/Thread;");
                    jmethodID get_context_class_loader = (*env)->GetMethodID(
                            env,
                            thread_class,
                            "getContextClassLoader",
                            "()Ljava/lang/ClassLoader;");
                    if (current_thread == NULL || get_context_class_loader == NULL) {
                        (*env)->DeleteLocalRef(env, thread_class);
                        return NULL;
                    }
                    jobject thread = (*env)->CallStaticObjectMethod(
                            env, thread_class, current_thread);
                    (*env)->DeleteLocalRef(env, thread_class);
                    if (thread == NULL) {
                        return NULL;
                    }
                    jobject loader = (*env)->CallObjectMethod(
                            env, thread, get_context_class_loader);
                    (*env)->DeleteLocalRef(env, thread);
                    return loader;
                }

                static jclass j2ll_class_for_registration(
                        JNIEnv* env, const char* internal_name) {
                    char* dotted = j2ll_dotted_class_name(internal_name);
                    if (dotted == NULL) {
                        j2ll_throw_new(
                                env,
                                "java/lang/OutOfMemoryError",
                                "failed to allocate class name");
                        return NULL;
                    }
                    jclass class_class = (*env)->FindClass(env, "java/lang/Class");
                    if (class_class == NULL) {
                        free(dotted);
                        return NULL;
                    }
                    jmethodID for_name = (*env)->GetStaticMethodID(
                            env,
                            class_class,
                            "forName",
                            "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;");
                    if (for_name == NULL) {
                        (*env)->DeleteLocalRef(env, class_class);
                        free(dotted);
                        return NULL;
                    }
                    jstring name = (*env)->NewStringUTF(env, dotted);
                    free(dotted);
                    if (name == NULL) {
                        (*env)->DeleteLocalRef(env, class_class);
                        return NULL;
                    }
                    jobject loader = j2ll_context_class_loader(env);
                    if ((*env)->ExceptionCheck(env)) {
                        (*env)->DeleteLocalRef(env, class_class);
                        (*env)->DeleteLocalRef(env, name);
                        if (loader != NULL) {
                            (*env)->DeleteLocalRef(env, loader);
                        }
                        return NULL;
                    }
                    jclass owner = (jclass)(*env)->CallStaticObjectMethod(
                            env,
                            class_class,
                            for_name,
                            name,
                            JNI_FALSE,
                            loader);
                    (*env)->DeleteLocalRef(env, class_class);
                    (*env)->DeleteLocalRef(env, name);
                    if (loader != NULL) {
                        (*env)->DeleteLocalRef(env, loader);
                    }
                    return owner;
                }

                """;
    }
}
