package xyz.melodysky.toolchain;

import java.util.List;
import java.util.TreeMap;
import xyz.melodysky.runtime.ClassIdentityToken;

final class HostJniAllocationRuntimeSource {
    private HostJniAllocationRuntimeSource() {}

    static void append(StringBuilder builder, List<HostJniCSourceGenerator.Binding> bindings) {
        List<String> allocationKeys = bindings.stream()
                .filter(binding -> binding.path() == NativeImplementationPath.LLVM_NATIVE_PATH)
                .flatMap(binding -> binding.allocationKeys().stream())
                .distinct()
                .sorted()
                .toList();
        List<String> typeCheckKeys = bindings.stream()
                .filter(binding -> binding.path() == NativeImplementationPath.LLVM_NATIVE_PATH)
                .flatMap(binding -> binding.typeCheckKeys().stream())
                .distinct()
                .sorted()
                .toList();
        TreeMap<String, ClassParts> classEntries = new TreeMap<>();
        builder.append("""
                typedef struct {
                    int64_t token;
                    int64_t class_init_token;
                    const char* class_name;
                } j2ll_class_entry;

                static const j2ll_class_entry j2ll_class_table[] = {
                """);
        bindings.stream()
                .filter(binding -> binding.path() == NativeImplementationPath.LLVM_NATIVE_PATH)
                .map(binding -> new ClassParts("L" + binding.decision().method().owner() + ";", binding.decision().method().owner()))
                .forEach(parts -> classEntries.putIfAbsent(parts.identity(), parts));
        bindings.stream()
                .filter(binding -> binding.path() == NativeImplementationPath.LLVM_NATIVE_PATH)
                .flatMap(binding -> binding.classObjectKeys().stream())
                .map(HostJniAllocationRuntimeSource::parseClassObjectKey)
                .forEach(parts -> classEntries.putIfAbsent(parts.identity(), parts));
        bindings.stream()
                .filter(binding -> binding.path() == NativeImplementationPath.LLVM_NATIVE_PATH)
                .flatMap(binding -> binding.runtimeMetadataKeys().stream())
                .filter(key -> key.startsWith("class:"))
                .map(HostJniAllocationRuntimeSource::parseClassObjectKey)
                .forEach(parts -> classEntries.putIfAbsent(parts.identity(), parts));
        for (String allocationKey : allocationKeys) {
            ClassParts parts = parseAllocationKey(allocationKey);
            classEntries.putIfAbsent(parts.identity(), parts);
        }
        for (String typeCheckKey : typeCheckKeys) {
            ClassParts parts = parseTypeCheckKey(typeCheckKey);
            classEntries.putIfAbsent(parts.identity(), parts);
        }
        for (ClassParts parts : classEntries.values()) {
            builder.append("    { ")
                    .append(ClassIdentityToken.token(parts.identity()))
                    .append("LL, ")
                    .append(stableClassObjectToken(parts.identity()))
                    .append("LL, \"")
                    .append(escapeCString(parts.jniName()))
                    .append("\" },\n");
        }
        builder.append("    { 0LL, 0LL, NULL },\n");
        builder.append("""
                };

                static const char* j2ll_find_class_name(int64_t token) {
                    for (size_t index = 0; index < sizeof(j2ll_class_table) / sizeof(j2ll_class_table[0]); index++) {
                        if (j2ll_class_table[index].class_name != NULL && j2ll_class_table[index].token == token) {
                            return j2ll_class_table[index].class_name;
                        }
                    }
                    return NULL;
                }

                static const char* j2ll_find_class_object_name(int64_t token) {
                    for (size_t index = 0; index < sizeof(j2ll_class_table) / sizeof(j2ll_class_table[0]); index++) {
                        if (j2ll_class_table[index].class_name != NULL && j2ll_class_table[index].class_init_token == token) {
                            return j2ll_class_table[index].class_name;
                        }
                    }
                    return NULL;
                }

                jobject j2ll_rt_alloc_object(JNIEnv* env, int64_t class_token) {
                    const char* class_name = j2ll_find_class_name(class_token);
                    if (class_name == NULL) {
                        j2ll_throw_new(env, "java/lang/NoClassDefFoundError", "unknown j2ll class token");
                        return NULL;
                    }
                    jclass cls = (*env)->FindClass(env, class_name);
                    if (cls == NULL) {
                        return NULL;
                    }
                    jobject object = (*env)->AllocObject(env, cls);
                    (*env)->DeleteLocalRef(env, cls);
                    return object;
                }

                jarray j2ll_rt_new_int_array(JNIEnv* env, int32_t length) {
                    if (length < 0) {
                        j2ll_throw_new(env, "java/lang/NegativeArraySizeException", "negative int array length");
                        return NULL;
                    }
                    return (jarray)(*env)->NewIntArray(env, (jsize)length);
                }

                jarray j2ll_rt_new_byte_array(JNIEnv* env, int32_t length) {
                    if (length < 0) {
                        j2ll_throw_new(env, "java/lang/NegativeArraySizeException", "negative byte array length");
                        return NULL;
                    }
                    return (jarray)(*env)->NewByteArray(env, (jsize)length);
                }

                jarray j2ll_rt_new_short_array(JNIEnv* env, int32_t length) {
                    if (length < 0) {
                        j2ll_throw_new(env, "java/lang/NegativeArraySizeException", "negative short array length");
                        return NULL;
                    }
                    return (jarray)(*env)->NewShortArray(env, (jsize)length);
                }

                jarray j2ll_rt_new_char_array(JNIEnv* env, int32_t length) {
                    if (length < 0) {
                        j2ll_throw_new(env, "java/lang/NegativeArraySizeException", "negative char array length");
                        return NULL;
                    }
                    return (jarray)(*env)->NewCharArray(env, (jsize)length);
                }

                jarray j2ll_rt_new_long_array(JNIEnv* env, int32_t length) {
                    if (length < 0) {
                        j2ll_throw_new(env, "java/lang/NegativeArraySizeException", "negative long array length");
                        return NULL;
                    }
                    return (jarray)(*env)->NewLongArray(env, (jsize)length);
                }

                jarray j2ll_rt_new_float_array(JNIEnv* env, int32_t length) {
                    if (length < 0) {
                        j2ll_throw_new(env, "java/lang/NegativeArraySizeException", "negative float array length");
                        return NULL;
                    }
                    return (jarray)(*env)->NewFloatArray(env, (jsize)length);
                }

                jarray j2ll_rt_new_double_array(JNIEnv* env, int32_t length) {
                    if (length < 0) {
                        j2ll_throw_new(env, "java/lang/NegativeArraySizeException", "negative double array length");
                        return NULL;
                    }
                    return (jarray)(*env)->NewDoubleArray(env, (jsize)length);
                }

                jarray j2ll_rt_new_object_array(JNIEnv* env, int64_t component_token, int32_t length) {
                    if (length < 0) {
                        j2ll_throw_new(env, "java/lang/NegativeArraySizeException", "negative object array length");
                        return NULL;
                    }
                    const char* class_name = j2ll_find_class_name(component_token);
                    if (class_name == NULL) {
                        j2ll_throw_new(env, "java/lang/NoClassDefFoundError", "unknown j2ll array component token");
                        return NULL;
                    }
                    jclass component = (*env)->FindClass(env, class_name);
                    if (component == NULL) {
                        return NULL;
                    }
                    jobjectArray array = (*env)->NewObjectArray(env, (jsize)length, component, NULL);
                    (*env)->DeleteLocalRef(env, component);
                    return (jarray)array;
                }

                """);
    }

    private static ClassParts parseAllocationKey(String allocationKey) {
        if (allocationKey.startsWith("object:")) {
            String internalName = allocationKey.substring("object:".length());
            return new ClassParts("L" + internalName + ";", internalName);
        }
        if (allocationKey.startsWith("referenceArray:")) {
            String component = allocationKey.substring("referenceArray:".length());
            if (component.startsWith("[")) {
                return new ClassParts(component, component);
            }
            return new ClassParts("L" + component + ";", component);
        }
        throw new IllegalArgumentException("invalid allocation key: " + allocationKey);
    }

    private static ClassParts parseTypeCheckKey(String typeCheckKey) {
        if (typeCheckKey.startsWith("checkcast:")) {
            return parseTypeIdentity(typeCheckKey.substring("checkcast:".length()));
        }
        if (typeCheckKey.startsWith("instanceof:")) {
            return parseTypeIdentity(typeCheckKey.substring("instanceof:".length()));
        }
        throw new IllegalArgumentException("invalid type check key: " + typeCheckKey);
    }

    private static ClassParts parseClassObjectKey(String classObjectKey) {
        if (!classObjectKey.startsWith("class:")) {
            throw new IllegalArgumentException("invalid class object key: " + classObjectKey);
        }
        return parseTypeIdentity(classObjectKey.substring("class:".length()));
    }

    private static ClassParts parseTypeIdentity(String internalOrDescriptor) {
        if (internalOrDescriptor.startsWith("[")) {
            return new ClassParts(internalOrDescriptor, internalOrDescriptor);
        }
        if (internalOrDescriptor.startsWith("L") && internalOrDescriptor.endsWith(";")) {
            String internalName = internalOrDescriptor.substring(1, internalOrDescriptor.length() - 1);
            return new ClassParts(internalOrDescriptor, internalName);
        }
        return new ClassParts("L" + internalOrDescriptor + ";", internalOrDescriptor);
    }

    private static long stableClassObjectToken(String classDescriptor) {
        // BytecodeToSsaLowerer hashes the descriptor operand itself. The
        // surrounding "class:" text belongs to the IR evidence symbol only
        // and must not participate in the runtime lookup token.
        return Integer.toUnsignedLong(classDescriptor.hashCode());
    }

    private static String escapeCString(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record ClassParts(String identity, String jniName) {}
}
