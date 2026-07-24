package xyz.melodysky.toolchain;

final class HostJniFallbackRuntimeSource {
    private HostJniFallbackRuntimeSource() {}

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
                    jmethodID current_thread = (*env)->GetStaticMethodID(env, thread_class, "currentThread", "()Ljava/lang/Thread;");
                    jmethodID get_context_class_loader = (*env)->GetMethodID(env, thread_class, "getContextClassLoader", "()Ljava/lang/ClassLoader;");
                    if (current_thread == NULL || get_context_class_loader == NULL) {
                        (*env)->DeleteLocalRef(env, thread_class);
                        return NULL;
                    }
                    jobject thread = (*env)->CallStaticObjectMethod(env, thread_class, current_thread);
                    (*env)->DeleteLocalRef(env, thread_class);
                    if (thread == NULL) {
                        return NULL;
                    }
                    jobject loader = (*env)->CallObjectMethod(env, thread, get_context_class_loader);
                    (*env)->DeleteLocalRef(env, thread);
                    return loader;
                }

                static jclass j2ll_class_for_registration(JNIEnv* env, const char* internal_name) {
                    char* dotted = j2ll_dotted_class_name(internal_name);
                    if (dotted == NULL) {
                        j2ll_throw_new(env, "java/lang/OutOfMemoryError", "failed to allocate class name");
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
                    jclass owner = (jclass)(*env)->CallStaticObjectMethod(env, class_class, for_name, name, JNI_FALSE, loader);
                    (*env)->DeleteLocalRef(env, class_class);
                    (*env)->DeleteLocalRef(env, name);
                    if (loader != NULL) {
                        (*env)->DeleteLocalRef(env, loader);
                    }
                    return owner;
                }

                """;
    }

    static String fallbackHelperSource(String loaderInternalName) {
        return """
                static int j2ll_verify_sha256_hex(JNIEnv* env, const unsigned char* bytes, size_t length, const char* expected_hex) {
                    jclass digest_class = (*env)->FindClass(env, "java/security/MessageDigest");
                    if (digest_class == NULL) {
                        return 0;
                    }
                    jmethodID get_instance = (*env)->GetStaticMethodID(
                            env,
                            digest_class,
                            "getInstance",
                            "(Ljava/lang/String;)Ljava/security/MessageDigest;");
                    jmethodID digest_method = (*env)->GetMethodID(env, digest_class, "digest", "([B)[B");
                    if (get_instance == NULL || digest_method == NULL) {
                        (*env)->DeleteLocalRef(env, digest_class);
                        return 0;
                    }
                    jstring algorithm = (*env)->NewStringUTF(env, "SHA-256");
                    if (algorithm == NULL) {
                        (*env)->DeleteLocalRef(env, digest_class);
                        return 0;
                    }
                    jobject digest = (*env)->CallStaticObjectMethod(env, digest_class, get_instance, algorithm);
                    (*env)->DeleteLocalRef(env, algorithm);
                    (*env)->DeleteLocalRef(env, digest_class);
                    if (digest == NULL) {
                        return 0;
                    }
                    if (length > 2147483647u) {
                        (*env)->DeleteLocalRef(env, digest);
                        j2ll_throw_new(env, "java/lang/SecurityException", "fallback blob too large to hash");
                        return 0;
                    }
                    jbyteArray input = (*env)->NewByteArray(env, (jsize)length);
                    if (input == NULL) {
                        (*env)->DeleteLocalRef(env, digest);
                        return 0;
                    }
                    if (length > 0) {
                        (*env)->SetByteArrayRegion(env, input, 0, (jsize)length, (const jbyte*)bytes);
                        if ((*env)->ExceptionCheck(env)) {
                            (*env)->DeleteLocalRef(env, input);
                            (*env)->DeleteLocalRef(env, digest);
                            return 0;
                        }
                    }
                    jbyteArray hash = (jbyteArray)(*env)->CallObjectMethod(env, digest, digest_method, input);
                    (*env)->DeleteLocalRef(env, input);
                    (*env)->DeleteLocalRef(env, digest);
                    if (hash == NULL) {
                        return 0;
                    }
                    jsize hash_length = (*env)->GetArrayLength(env, hash);
                    if (hash_length != 32) {
                        (*env)->DeleteLocalRef(env, hash);
                        j2ll_throw_new(env, "java/lang/SecurityException", "fallback SHA-256 digest length mismatch");
                        return 0;
                    }
                    jbyte hash_bytes[32];
                    (*env)->GetByteArrayRegion(env, hash, 0, 32, hash_bytes);
                    (*env)->DeleteLocalRef(env, hash);
                    if ((*env)->ExceptionCheck(env)) {
                        return 0;
                    }
                    static const char hex[] = "0123456789abcdef";
                    char actual[65];
                    for (int index = 0; index < 32; index++) {
                        unsigned char value = (unsigned char)hash_bytes[index];
                        actual[index * 2] = hex[value >> 4];
                        actual[index * 2 + 1] = hex[value & 0x0f];
                    }
                    actual[64] = '\\0';
                    return strcmp(actual, expected_hex) == 0;
                }

                static jobject j2ll_owner_class_loader(JNIEnv* env, jclass owner) {
                    jclass class_class = (*env)->FindClass(env, "java/lang/Class");
                    if (class_class == NULL) {
                        return NULL;
                    }
                    jmethodID get_class_loader = (*env)->GetMethodID(env, class_class, "getClassLoader", "()Ljava/lang/ClassLoader;");
                    (*env)->DeleteLocalRef(env, class_class);
                    if (get_class_loader == NULL) {
                        return NULL;
                    }
                    return (*env)->CallObjectMethod(env, owner, get_class_loader);
                }

                static jclass j2ll_try_define_hidden_fallback(JNIEnv* env, jclass owner, const unsigned char* bytes, size_t length) {
                    if (length > 2147483647u) {
                        return NULL;
                    }
                    jclass support = (*env)->FindClass(env, "@LOADER_INTERNAL_NAME@");
                    if (support == NULL) {
                        if ((*env)->ExceptionCheck(env)) {
                            (*env)->ExceptionClear(env);
                        }
                        return NULL;
                    }
                    jmethodID define_hidden = (*env)->GetStaticMethodID(
                            env,
                            support,
                            "defineHiddenFallback",
                            "(Ljava/lang/Class;[B)Ljava/lang/Class;");
                    if (define_hidden == NULL) {
                        (*env)->DeleteLocalRef(env, support);
                        if ((*env)->ExceptionCheck(env)) {
                            (*env)->ExceptionClear(env);
                        }
                        return NULL;
                    }
                    jbyteArray bytecode = (*env)->NewByteArray(env, (jsize)length);
                    if (bytecode == NULL) {
                        (*env)->DeleteLocalRef(env, support);
                        if ((*env)->ExceptionCheck(env)) {
                            (*env)->ExceptionClear(env);
                        }
                        return NULL;
                    }
                    if (length > 0) {
                        (*env)->SetByteArrayRegion(env, bytecode, 0, (jsize)length, (const jbyte*)bytes);
                        if ((*env)->ExceptionCheck(env)) {
                            (*env)->ExceptionClear(env);
                            (*env)->DeleteLocalRef(env, bytecode);
                            (*env)->DeleteLocalRef(env, support);
                            return NULL;
                        }
                    }
                    jclass hidden = (jclass)(*env)->CallStaticObjectMethod(env, support, define_hidden, owner, bytecode);
                    (*env)->DeleteLocalRef(env, bytecode);
                    (*env)->DeleteLocalRef(env, support);
                    if ((*env)->ExceptionCheck(env)) {
                        (*env)->ExceptionClear(env);
                        return NULL;
                    }
                    return hidden;
                }

                """.replace(
                "@LOADER_INTERNAL_NAME@",
                CSourceEscaper.stringContents(loaderInternalName));
    }

}
