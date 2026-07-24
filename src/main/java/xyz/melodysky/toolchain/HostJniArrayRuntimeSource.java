package xyz.melodysky.toolchain;

final class HostJniArrayRuntimeSource {
    private HostJniArrayRuntimeSource() {}

    static String arrayHelperSource() {
        return """
                int32_t j2ll_rt_array_length_i32(JNIEnv* env, jarray array) {
                    if (array == NULL) {
                        if ((*env)->ExceptionCheck(env)) {
                            return 0;
                        }
                        j2ll_throw_new(env, "java/lang/NullPointerException", "array is null");
                        return 0;
                    }
                    return (*env)->GetArrayLength(env, array);
                }

                int32_t j2ll_rt_array_load_i8(JNIEnv* env, jarray array, int32_t index) {
                    if (array == NULL) {
                        if ((*env)->ExceptionCheck(env)) {
                            return 0;
                        }
                        j2ll_throw_new(env, "java/lang/NullPointerException", "array is null");
                        return 0;
                    }
                    jsize length = (*env)->GetArrayLength(env, array);
                    if (index < 0 || index >= length) {
                        j2ll_throw_new(env, "java/lang/ArrayIndexOutOfBoundsException", "byte array index out of bounds");
                        return 0;
                    }
                    jbyte value = 0;
                    (*env)->GetByteArrayRegion(env, (jbyteArray)array, index, 1, &value);
                    return (int32_t)value;
                }

                void j2ll_rt_array_store_i8(JNIEnv* env, jarray array, int32_t index, int32_t value) {
                    if (array == NULL) {
                        if ((*env)->ExceptionCheck(env)) {
                            return;
                        }
                        j2ll_throw_new(env, "java/lang/NullPointerException", "array is null");
                        return;
                    }
                    jsize length = (*env)->GetArrayLength(env, array);
                    if (index < 0 || index >= length) {
                        j2ll_throw_new(env, "java/lang/ArrayIndexOutOfBoundsException", "byte array index out of bounds");
                        return;
                    }
                    jbyte copy = (jbyte)value;
                    (*env)->SetByteArrayRegion(env, (jbyteArray)array, index, 1, &copy);
                }

                int32_t j2ll_rt_array_load_i16(JNIEnv* env, jarray array, int32_t index) {
                    if (array == NULL) {
                        if ((*env)->ExceptionCheck(env)) {
                            return 0;
                        }
                        j2ll_throw_new(env, "java/lang/NullPointerException", "array is null");
                        return 0;
                    }
                    jsize length = (*env)->GetArrayLength(env, array);
                    if (index < 0 || index >= length) {
                        j2ll_throw_new(env, "java/lang/ArrayIndexOutOfBoundsException", "short array index out of bounds");
                        return 0;
                    }
                    jshort value = 0;
                    (*env)->GetShortArrayRegion(env, (jshortArray)array, index, 1, &value);
                    return (int32_t)value;
                }

                void j2ll_rt_array_store_i16(JNIEnv* env, jarray array, int32_t index, int32_t value) {
                    if (array == NULL) {
                        if ((*env)->ExceptionCheck(env)) {
                            return;
                        }
                        j2ll_throw_new(env, "java/lang/NullPointerException", "array is null");
                        return;
                    }
                    jsize length = (*env)->GetArrayLength(env, array);
                    if (index < 0 || index >= length) {
                        j2ll_throw_new(env, "java/lang/ArrayIndexOutOfBoundsException", "short array index out of bounds");
                        return;
                    }
                    jshort copy = (jshort)value;
                    (*env)->SetShortArrayRegion(env, (jshortArray)array, index, 1, &copy);
                }

                int32_t j2ll_rt_array_load_u16(JNIEnv* env, jarray array, int32_t index) {
                    if (array == NULL) {
                        if ((*env)->ExceptionCheck(env)) {
                            return 0;
                        }
                        j2ll_throw_new(env, "java/lang/NullPointerException", "array is null");
                        return 0;
                    }
                    jsize length = (*env)->GetArrayLength(env, array);
                    if (index < 0 || index >= length) {
                        j2ll_throw_new(env, "java/lang/ArrayIndexOutOfBoundsException", "char array index out of bounds");
                        return 0;
                    }
                    jchar value = 0;
                    (*env)->GetCharArrayRegion(env, (jcharArray)array, index, 1, &value);
                    return (int32_t)value;
                }

                void j2ll_rt_array_store_u16(JNIEnv* env, jarray array, int32_t index, int32_t value) {
                    if (array == NULL) {
                        if ((*env)->ExceptionCheck(env)) {
                            return;
                        }
                        j2ll_throw_new(env, "java/lang/NullPointerException", "array is null");
                        return;
                    }
                    jsize length = (*env)->GetArrayLength(env, array);
                    if (index < 0 || index >= length) {
                        j2ll_throw_new(env, "java/lang/ArrayIndexOutOfBoundsException", "char array index out of bounds");
                        return;
                    }
                    jchar copy = (jchar)value;
                    (*env)->SetCharArrayRegion(env, (jcharArray)array, index, 1, &copy);
                }

                int32_t j2ll_rt_array_load_i32(JNIEnv* env, jarray array, int32_t index) {
                    if (array == NULL) {
                        if ((*env)->ExceptionCheck(env)) {
                            return 0;
                        }
                        j2ll_throw_new(env, "java/lang/NullPointerException", "array is null");
                        return 0;
                    }
                    jsize length = (*env)->GetArrayLength(env, array);
                    if (index < 0 || index >= length) {
                        j2ll_throw_new(env, "java/lang/ArrayIndexOutOfBoundsException", "int array index out of bounds");
                        return 0;
                    }
                    jint value = 0;
                    (*env)->GetIntArrayRegion(env, (jintArray)array, index, 1, &value);
                    return value;
                }

                void j2ll_rt_array_store_i32(JNIEnv* env, jarray array, int32_t index, int32_t value) {
                    if (array == NULL) {
                        if ((*env)->ExceptionCheck(env)) {
                            return;
                        }
                        j2ll_throw_new(env, "java/lang/NullPointerException", "array is null");
                        return;
                    }
                    jsize length = (*env)->GetArrayLength(env, array);
                    if (index < 0 || index >= length) {
                        j2ll_throw_new(env, "java/lang/ArrayIndexOutOfBoundsException", "int array index out of bounds");
                        return;
                    }
                    jint copy = value;
                    (*env)->SetIntArrayRegion(env, (jintArray)array, index, 1, &copy);
                }

                int64_t j2ll_rt_array_load_i64(JNIEnv* env, jarray array, int32_t index) {
                    if (array == NULL) {
                        if ((*env)->ExceptionCheck(env)) {
                            return 0;
                        }
                        j2ll_throw_new(env, "java/lang/NullPointerException", "array is null");
                        return 0;
                    }
                    jsize length = (*env)->GetArrayLength(env, array);
                    if (index < 0 || index >= length) {
                        j2ll_throw_new(env, "java/lang/ArrayIndexOutOfBoundsException", "long array index out of bounds");
                        return 0;
                    }
                    jlong value = 0;
                    (*env)->GetLongArrayRegion(env, (jlongArray)array, index, 1, &value);
                    return (int64_t)value;
                }

                void j2ll_rt_array_store_i64(JNIEnv* env, jarray array, int32_t index, int64_t value) {
                    if (array == NULL) {
                        if ((*env)->ExceptionCheck(env)) {
                            return;
                        }
                        j2ll_throw_new(env, "java/lang/NullPointerException", "array is null");
                        return;
                    }
                    jsize length = (*env)->GetArrayLength(env, array);
                    if (index < 0 || index >= length) {
                        j2ll_throw_new(env, "java/lang/ArrayIndexOutOfBoundsException", "long array index out of bounds");
                        return;
                    }
                    jlong copy = (jlong)value;
                    (*env)->SetLongArrayRegion(env, (jlongArray)array, index, 1, &copy);
                }

                float j2ll_rt_array_load_f32(JNIEnv* env, jarray array, int32_t index) {
                    if (array == NULL) {
                        if ((*env)->ExceptionCheck(env)) {
                            return 0.0f;
                        }
                        j2ll_throw_new(env, "java/lang/NullPointerException", "array is null");
                        return 0.0f;
                    }
                    jsize length = (*env)->GetArrayLength(env, array);
                    if (index < 0 || index >= length) {
                        j2ll_throw_new(env, "java/lang/ArrayIndexOutOfBoundsException", "float array index out of bounds");
                        return 0.0f;
                    }
                    jfloat value = 0.0f;
                    (*env)->GetFloatArrayRegion(env, (jfloatArray)array, index, 1, &value);
                    return value;
                }

                void j2ll_rt_array_store_f32(JNIEnv* env, jarray array, int32_t index, float value) {
                    if (array == NULL) {
                        if ((*env)->ExceptionCheck(env)) {
                            return;
                        }
                        j2ll_throw_new(env, "java/lang/NullPointerException", "array is null");
                        return;
                    }
                    jsize length = (*env)->GetArrayLength(env, array);
                    if (index < 0 || index >= length) {
                        j2ll_throw_new(env, "java/lang/ArrayIndexOutOfBoundsException", "float array index out of bounds");
                        return;
                    }
                    jfloat copy = (jfloat)value;
                    (*env)->SetFloatArrayRegion(env, (jfloatArray)array, index, 1, &copy);
                }

                double j2ll_rt_array_load_f64(JNIEnv* env, jarray array, int32_t index) {
                    if (array == NULL) {
                        if ((*env)->ExceptionCheck(env)) {
                            return 0.0;
                        }
                        j2ll_throw_new(env, "java/lang/NullPointerException", "array is null");
                        return 0.0;
                    }
                    jsize length = (*env)->GetArrayLength(env, array);
                    if (index < 0 || index >= length) {
                        j2ll_throw_new(env, "java/lang/ArrayIndexOutOfBoundsException", "double array index out of bounds");
                        return 0.0;
                    }
                    jdouble value = 0.0;
                    (*env)->GetDoubleArrayRegion(env, (jdoubleArray)array, index, 1, &value);
                    return value;
                }

                void j2ll_rt_array_store_f64(JNIEnv* env, jarray array, int32_t index, double value) {
                    if (array == NULL) {
                        if ((*env)->ExceptionCheck(env)) {
                            return;
                        }
                        j2ll_throw_new(env, "java/lang/NullPointerException", "array is null");
                        return;
                    }
                    jsize length = (*env)->GetArrayLength(env, array);
                    if (index < 0 || index >= length) {
                        j2ll_throw_new(env, "java/lang/ArrayIndexOutOfBoundsException", "double array index out of bounds");
                        return;
                    }
                    jdouble copy = (jdouble)value;
                    (*env)->SetDoubleArrayRegion(env, (jdoubleArray)array, index, 1, &copy);
                }

                jobject j2ll_rt_array_load_ref(JNIEnv* env, jarray array, int32_t index) {
                    if (array == NULL) {
                        if ((*env)->ExceptionCheck(env)) {
                            return NULL;
                        }
                        j2ll_throw_new(env, "java/lang/NullPointerException", "array is null");
                        return NULL;
                    }
                    jsize length = (*env)->GetArrayLength(env, array);
                    if (index < 0 || index >= length) {
                        j2ll_throw_new(env, "java/lang/ArrayIndexOutOfBoundsException", "object array index out of bounds");
                        return NULL;
                    }
                    return (*env)->GetObjectArrayElement(env, (jobjectArray)array, index);
                }

                void j2ll_rt_array_store_ref(JNIEnv* env, jarray array, int32_t index, jobject value) {
                    if (array == NULL) {
                        if ((*env)->ExceptionCheck(env)) {
                            return;
                        }
                        j2ll_throw_new(env, "java/lang/NullPointerException", "array is null");
                        return;
                    }
                    jsize length = (*env)->GetArrayLength(env, array);
                    if (index < 0 || index >= length) {
                        j2ll_throw_new(env, "java/lang/ArrayIndexOutOfBoundsException", "object array index out of bounds");
                        return;
                    }
                    (*env)->SetObjectArrayElement(env, (jobjectArray)array, index, value);
                }

                """;
    }

}
