package xyz.melodysky.toolchain;

import java.util.List;
import xyz.melodysky.runtime.FieldIdentityToken;

final class HostJniFieldRuntimeSource {
    private HostJniFieldRuntimeSource() {}

    static void append(StringBuilder builder, List<HostJniCSourceGenerator.Binding> bindings) {
        List<String> fieldKeys = bindings.stream()
                .filter(binding -> binding.path() == NativeImplementationPath.LLVM_NATIVE_PATH)
                .flatMap(binding -> binding.fieldKeys().stream())
                .filter(fieldKey -> !fieldKey.startsWith("native-slot:"))
                .distinct()
                .sorted()
                .toList();
        if (fieldKeys.isEmpty()) {
            return;
        }
        builder.append("""
                typedef struct {
                    int64_t token;
                    const char* owner;
                    const char* name;
                    const char* descriptor;
                } j2ll_field_entry;

                static const j2ll_field_entry j2ll_field_table[] = {
                """);
        for (String fieldKey : fieldKeys) {
            FieldParts parts = parseFieldKey(fieldKey);
            builder.append("    { ")
                    .append(FieldIdentityToken.token(fieldKey))
                    .append("LL, \"")
                    .append(escapeCString(parts.owner()))
                    .append("\", \"")
                    .append(escapeCString(parts.name()))
                    .append("\", \"")
                    .append(escapeCString(parts.descriptor()))
                    .append("\" },\n");
        }
        builder.append("""
                };

                static const j2ll_field_entry* j2ll_find_field(int64_t token) {
                    for (size_t index = 0; index < sizeof(j2ll_field_table) / sizeof(j2ll_field_table[0]); index++) {
                        if (j2ll_field_table[index].token == token) {
                            return &j2ll_field_table[index];
                        }
                    }
                    return NULL;
                }

                static jclass j2ll_static_field_class(JNIEnv* env, jclass owner, const j2ll_field_entry* entry, int* local_ref) {
                    (void)owner;
                    *local_ref = 0;
                    jclass cls = (*env)->FindClass(env, entry->owner);
                    if (cls != NULL) {
                        *local_ref = 1;
                    }
                    return cls;
                }

                static jfieldID j2ll_static_field_id(JNIEnv* env, jclass owner, int64_t token, jclass* resolved_class, int* local_ref) {
                    const j2ll_field_entry* entry = j2ll_find_field(token);
                    if (entry == NULL) {
                        j2ll_throw_new(env, "java/lang/NoSuchFieldError", "unknown j2ll field token");
                        return NULL;
                    }
                    *resolved_class = j2ll_static_field_class(env, owner, entry, local_ref);
                    if (*resolved_class == NULL) {
                        return NULL;
                    }
                    return (*env)->GetStaticFieldID(env, *resolved_class, entry->name, entry->descriptor);
                }

                static jfieldID j2ll_instance_field_id(JNIEnv* env, jobject self, int64_t token, jclass* resolved_class) {
                    if (self == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "field receiver is null");
                        return NULL;
                    }
                    const j2ll_field_entry* entry = j2ll_find_field(token);
                    if (entry == NULL) {
                        j2ll_throw_new(env, "java/lang/NoSuchFieldError", "unknown j2ll field token");
                        return NULL;
                    }
                    *resolved_class = (*env)->GetObjectClass(env, self);
                    if (*resolved_class == NULL) {
                        return NULL;
                    }
                    return (*env)->GetFieldID(env, *resolved_class, entry->name, entry->descriptor);
                }

                int32_t j2ll_rt_field_get_static_i32(JNIEnv* env, jclass owner, int64_t token) {
                    jclass cls = NULL;
                    int local_ref = 0;
                    jfieldID field = j2ll_static_field_id(env, owner, token, &cls, &local_ref);
                    if (field == NULL) {
                        return 0;
                    }
                    const j2ll_field_entry* entry = j2ll_find_field(token);
                    jint value = 0;
                    switch (entry->descriptor[0]) {
                        case 'Z':
                            value = (*env)->GetStaticBooleanField(env, cls, field) == JNI_FALSE ? 0 : 1;
                            break;
                        case 'B':
                            value = (jint)(*env)->GetStaticByteField(env, cls, field);
                            break;
                        case 'S':
                            value = (jint)(*env)->GetStaticShortField(env, cls, field);
                            break;
                        case 'C':
                            value = (jint)(*env)->GetStaticCharField(env, cls, field);
                            break;
                        default:
                            value = (*env)->GetStaticIntField(env, cls, field);
                            break;
                    }
                    if (local_ref) {
                        (*env)->DeleteLocalRef(env, cls);
                    }
                    return value;
                }

                void j2ll_rt_field_put_static_i32(JNIEnv* env, jclass owner, int64_t token, int32_t value) {
                    jclass cls = NULL;
                    int local_ref = 0;
                    jfieldID field = j2ll_static_field_id(env, owner, token, &cls, &local_ref);
                    if (field != NULL) {
                        const j2ll_field_entry* entry = j2ll_find_field(token);
                        switch (entry->descriptor[0]) {
                            case 'Z':
                                (*env)->SetStaticBooleanField(
                                        env, cls, field, (jboolean)((uint32_t)value & UINT32_C(1)));
                                break;
                            case 'B':
                                (*env)->SetStaticByteField(env, cls, field, (jbyte)value);
                                break;
                            case 'S':
                                (*env)->SetStaticShortField(env, cls, field, (jshort)value);
                                break;
                            case 'C':
                                (*env)->SetStaticCharField(env, cls, field, (jchar)value);
                                break;
                            default:
                                (*env)->SetStaticIntField(env, cls, field, value);
                                break;
                        }
                    }
                    if (local_ref && cls != NULL) {
                        (*env)->DeleteLocalRef(env, cls);
                    }
                }

                int64_t j2ll_rt_field_get_static_i64(JNIEnv* env, jclass owner, int64_t token) {
                    jclass cls = NULL;
                    int local_ref = 0;
                    jfieldID field = j2ll_static_field_id(env, owner, token, &cls, &local_ref);
                    if (field == NULL) {
                        return 0;
                    }
                    jlong value = (*env)->GetStaticLongField(env, cls, field);
                    if (local_ref) {
                        (*env)->DeleteLocalRef(env, cls);
                    }
                    return value;
                }

                void j2ll_rt_field_put_static_i64(JNIEnv* env, jclass owner, int64_t token, int64_t value) {
                    jclass cls = NULL;
                    int local_ref = 0;
                    jfieldID field = j2ll_static_field_id(env, owner, token, &cls, &local_ref);
                    if (field != NULL) {
                        (*env)->SetStaticLongField(env, cls, field, value);
                    }
                    if (local_ref && cls != NULL) {
                        (*env)->DeleteLocalRef(env, cls);
                    }
                }

                float j2ll_rt_field_get_static_f32(JNIEnv* env, jclass owner, int64_t token) {
                    jclass cls = NULL;
                    int local_ref = 0;
                    jfieldID field = j2ll_static_field_id(env, owner, token, &cls, &local_ref);
                    if (field == NULL) {
                        return 0.0f;
                    }
                    jfloat value = (*env)->GetStaticFloatField(env, cls, field);
                    if (local_ref) {
                        (*env)->DeleteLocalRef(env, cls);
                    }
                    return value;
                }

                void j2ll_rt_field_put_static_f32(JNIEnv* env, jclass owner, int64_t token, float value) {
                    jclass cls = NULL;
                    int local_ref = 0;
                    jfieldID field = j2ll_static_field_id(env, owner, token, &cls, &local_ref);
                    if (field != NULL) {
                        (*env)->SetStaticFloatField(env, cls, field, (jfloat)value);
                    }
                    if (local_ref && cls != NULL) {
                        (*env)->DeleteLocalRef(env, cls);
                    }
                }

                double j2ll_rt_field_get_static_f64(JNIEnv* env, jclass owner, int64_t token) {
                    jclass cls = NULL;
                    int local_ref = 0;
                    jfieldID field = j2ll_static_field_id(env, owner, token, &cls, &local_ref);
                    if (field == NULL) {
                        return 0.0;
                    }
                    jdouble value = (*env)->GetStaticDoubleField(env, cls, field);
                    if (local_ref) {
                        (*env)->DeleteLocalRef(env, cls);
                    }
                    return value;
                }

                void j2ll_rt_field_put_static_f64(JNIEnv* env, jclass owner, int64_t token, double value) {
                    jclass cls = NULL;
                    int local_ref = 0;
                    jfieldID field = j2ll_static_field_id(env, owner, token, &cls, &local_ref);
                    if (field != NULL) {
                        (*env)->SetStaticDoubleField(env, cls, field, (jdouble)value);
                    }
                    if (local_ref && cls != NULL) {
                        (*env)->DeleteLocalRef(env, cls);
                    }
                }

                jobject j2ll_rt_field_get_static_ref(JNIEnv* env, jclass owner, int64_t token) {
                    jclass cls = NULL;
                    int local_ref = 0;
                    jfieldID field = j2ll_static_field_id(env, owner, token, &cls, &local_ref);
                    if (field == NULL) {
                        return NULL;
                    }
                    jobject value = (*env)->GetStaticObjectField(env, cls, field);
                    if (local_ref) {
                        (*env)->DeleteLocalRef(env, cls);
                    }
                    return value;
                }

                void j2ll_rt_field_put_static_ref(JNIEnv* env, jclass owner, int64_t token, jobject value) {
                    jclass cls = NULL;
                    int local_ref = 0;
                    jfieldID field = j2ll_static_field_id(env, owner, token, &cls, &local_ref);
                    if (field != NULL) {
                        (*env)->SetStaticObjectField(env, cls, field, value);
                    }
                    if (local_ref && cls != NULL) {
                        (*env)->DeleteLocalRef(env, cls);
                    }
                }

                int32_t j2ll_rt_field_get_field_i32(JNIEnv* env, jobject self, int64_t token) {
                    jclass cls = NULL;
                    jfieldID field = j2ll_instance_field_id(env, self, token, &cls);
                    if (field == NULL) {
                        return 0;
                    }
                    const j2ll_field_entry* entry = j2ll_find_field(token);
                    jint value = 0;
                    switch (entry->descriptor[0]) {
                        case 'Z':
                            value = (*env)->GetBooleanField(env, self, field) == JNI_FALSE ? 0 : 1;
                            break;
                        case 'B':
                            value = (jint)(*env)->GetByteField(env, self, field);
                            break;
                        case 'S':
                            value = (jint)(*env)->GetShortField(env, self, field);
                            break;
                        case 'C':
                            value = (jint)(*env)->GetCharField(env, self, field);
                            break;
                        default:
                            value = (*env)->GetIntField(env, self, field);
                            break;
                    }
                    (*env)->DeleteLocalRef(env, cls);
                    return value;
                }

                void j2ll_rt_field_put_field_i32(JNIEnv* env, jobject self, int64_t token, int32_t value) {
                    jclass cls = NULL;
                    jfieldID field = j2ll_instance_field_id(env, self, token, &cls);
                    if (field != NULL) {
                        const j2ll_field_entry* entry = j2ll_find_field(token);
                        switch (entry->descriptor[0]) {
                            case 'Z':
                                (*env)->SetBooleanField(
                                        env, self, field, (jboolean)((uint32_t)value & UINT32_C(1)));
                                break;
                            case 'B':
                                (*env)->SetByteField(env, self, field, (jbyte)value);
                                break;
                            case 'S':
                                (*env)->SetShortField(env, self, field, (jshort)value);
                                break;
                            case 'C':
                                (*env)->SetCharField(env, self, field, (jchar)value);
                                break;
                            default:
                                (*env)->SetIntField(env, self, field, value);
                                break;
                        }
                        (*env)->DeleteLocalRef(env, cls);
                    }
                }

                int64_t j2ll_rt_field_get_field_i64(JNIEnv* env, jobject self, int64_t token) {
                    jclass cls = NULL;
                    jfieldID field = j2ll_instance_field_id(env, self, token, &cls);
                    if (field == NULL) {
                        return 0;
                    }
                    jlong value = (*env)->GetLongField(env, self, field);
                    (*env)->DeleteLocalRef(env, cls);
                    return value;
                }

                void j2ll_rt_field_put_field_i64(JNIEnv* env, jobject self, int64_t token, int64_t value) {
                    jclass cls = NULL;
                    jfieldID field = j2ll_instance_field_id(env, self, token, &cls);
                    if (field != NULL) {
                        (*env)->SetLongField(env, self, field, value);
                        (*env)->DeleteLocalRef(env, cls);
                    }
                }

                float j2ll_rt_field_get_field_f32(JNIEnv* env, jobject self, int64_t token) {
                    jclass cls = NULL;
                    jfieldID field = j2ll_instance_field_id(env, self, token, &cls);
                    if (field == NULL) {
                        return 0.0f;
                    }
                    jfloat value = (*env)->GetFloatField(env, self, field);
                    (*env)->DeleteLocalRef(env, cls);
                    return value;
                }

                void j2ll_rt_field_put_field_f32(JNIEnv* env, jobject self, int64_t token, float value) {
                    jclass cls = NULL;
                    jfieldID field = j2ll_instance_field_id(env, self, token, &cls);
                    if (field != NULL) {
                        (*env)->SetFloatField(env, self, field, (jfloat)value);
                        (*env)->DeleteLocalRef(env, cls);
                    }
                }

                double j2ll_rt_field_get_field_f64(JNIEnv* env, jobject self, int64_t token) {
                    jclass cls = NULL;
                    jfieldID field = j2ll_instance_field_id(env, self, token, &cls);
                    if (field == NULL) {
                        return 0.0;
                    }
                    jdouble value = (*env)->GetDoubleField(env, self, field);
                    (*env)->DeleteLocalRef(env, cls);
                    return value;
                }

                void j2ll_rt_field_put_field_f64(JNIEnv* env, jobject self, int64_t token, double value) {
                    jclass cls = NULL;
                    jfieldID field = j2ll_instance_field_id(env, self, token, &cls);
                    if (field != NULL) {
                        (*env)->SetDoubleField(env, self, field, (jdouble)value);
                        (*env)->DeleteLocalRef(env, cls);
                    }
                }

                jobject j2ll_rt_field_get_field_ref(JNIEnv* env, jobject self, int64_t token) {
                    jclass cls = NULL;
                    jfieldID field = j2ll_instance_field_id(env, self, token, &cls);
                    if (field == NULL) {
                        return NULL;
                    }
                    jobject value = (*env)->GetObjectField(env, self, field);
                    (*env)->DeleteLocalRef(env, cls);
                    return value;
                }

                void j2ll_rt_field_put_field_ref(JNIEnv* env, jobject self, int64_t token, jobject value) {
                    jclass cls = NULL;
                    jfieldID field = j2ll_instance_field_id(env, self, token, &cls);
                    if (field != NULL) {
                        (*env)->SetObjectField(env, self, field, value);
                        (*env)->DeleteLocalRef(env, cls);
                    }
                }

                """);
    }


    private static FieldParts parseFieldKey(String fieldKey) {
        int ownerEnd = fieldKey.indexOf('#');
        int descriptorStart = fieldKey.indexOf('!');
        if (ownerEnd < 0 || descriptorStart < ownerEnd) {
            throw new IllegalArgumentException("invalid field key: " + fieldKey);
        }
        return new FieldParts(
                fieldKey.substring(0, ownerEnd),
                fieldKey.substring(ownerEnd + 1, descriptorStart),
                fieldKey.substring(descriptorStart + 1));
    }

    private static String escapeCString(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record FieldParts(String owner, String name, String descriptor) {}
}
