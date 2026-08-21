package xyz.melodysky.toolchain;

import java.util.List;
import xyz.melodysky.runtime.RuntimeTokenDomain;
import xyz.melodysky.runtime.RuntimeTokenMapper;

final class HostJniAllocationRuntimeSource {
    private HostJniAllocationRuntimeSource() {}

    static boolean emitsClassForNameSupport(
            List<HostJniCSourceGenerator.Binding> bindings) {
        return bindings.stream()
                .filter(binding ->
                        binding.path()
                                == NativeImplementationPath.LLVM_NATIVE_PATH)
                .flatMap(binding ->
                        binding.runtimeMetadataKeys().stream())
                .anyMatch(key -> key.startsWith("class:"));
    }

    static void append(
            StringBuilder builder,
            List<HostJniCSourceGenerator.Binding> bindings,
            RuntimeTokenMapper runtimeTokens) {
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
        List<String> classObjectKeys = bindings.stream()
                .filter(binding -> binding.path() == NativeImplementationPath.LLVM_NATIVE_PATH)
                .flatMap(binding -> binding.classObjectKeys().stream())
                .distinct()
                .sorted()
                .toList();
        List<String> classForNameKeys = bindings.stream()
                .filter(binding -> binding.path() == NativeImplementationPath.LLVM_NATIVE_PATH)
                .flatMap(binding -> binding.runtimeMetadataKeys().stream())
                .filter(key -> key.startsWith("class:"))
                .distinct()
                .sorted()
                .toList();
        for (String allocationKey : runtimeTokens.physicalOrder(
                RuntimeTokenDomain.CLASS_RUNTIME,
                allocationKeys,
                key -> key)) {
            ClassParts parts = parseAllocationKey(allocationKey);
            if (allocationKey.startsWith("object:")) {
                appendObjectAllocator(builder, runtimeTokens, allocationKey, parts);
            } else {
                appendReferenceArrayAllocator(
                        builder,
                        runtimeTokens,
                        allocationKey,
                        parts);
            }
        }
        for (String typeCheckKey : runtimeTokens.physicalOrder(
                RuntimeTokenDomain.CLASS_RUNTIME,
                typeCheckKeys,
                key -> key)) {
            appendTypeCheck(
                    builder,
                    runtimeTokens,
                    typeCheckKey,
                    parseTypeCheckKey(typeCheckKey));
        }
        for (String classObjectKey : runtimeTokens.physicalOrder(
                RuntimeTokenDomain.CLASS_OBJECT,
                classObjectKeys,
                key -> key)) {
            appendClassObject(
                    builder,
                    runtimeTokens,
                    classObjectKey,
                    parseClassObjectKey(classObjectKey));
        }
        if (!classForNameKeys.isEmpty()) {
            builder.append("""
                    static jclass j2ll_class_for_name_with_init(
                            JNIEnv* env,
                            const char* internal_name,
                            const char* dotted_name,
                            int initialize);

                    """);
        }
        for (String classForNameKey : runtimeTokens.physicalOrder(
                RuntimeTokenDomain.CLASS_OBJECT,
                classForNameKeys,
                key -> key)) {
            ClassParts parts = parseClassObjectKey(classForNameKey);
            String symbol = runtimeTokens.helperSymbol(
                    RuntimeTokenDomain.CLASS_OBJECT,
                    "class_for_name",
                    parts.identity());
            builder.append("jclass ")
                    .append(symbol)
                    .append("(JNIEnv* env, int32_t initialize) {\n")
                    .append("    return j2ll_class_for_name_with_init(env, \"")
                    .append(escapeCString(parts.jniName()))
                    .append("\", \"")
                    .append(escapeCString(parts.jniName().replace('/', '.')))
                    .append("\", initialize);\n")
                    .append("}\n\n");
        }
        builder.append(runtimeHelperBodies());
    }

    private static void appendObjectAllocator(
            StringBuilder builder,
            RuntimeTokenMapper runtimeTokens,
            String allocationKey,
            ClassParts parts) {
        String symbol = runtimeTokens.helperSymbol(
                RuntimeTokenDomain.CLASS_RUNTIME,
                "alloc_object",
                allocationKey);
        builder.append("jobject ")
                .append(symbol)
                .append("(JNIEnv* env) {\n")
                .append("    jclass cls = (*env)->FindClass(env, \"")
                .append(escapeCString(parts.jniName()))
                .append("\");\n")
                .append("    if (cls == NULL) return NULL;\n")
                .append("    jobject object = (*env)->AllocObject(env, cls);\n")
                .append("    (*env)->DeleteLocalRef(env, cls);\n")
                .append("    return object;\n")
                .append("}\n\n");
    }

    private static void appendReferenceArrayAllocator(
            StringBuilder builder,
            RuntimeTokenMapper runtimeTokens,
            String allocationKey,
            ClassParts parts) {
        String symbol = runtimeTokens.helperSymbol(
                RuntimeTokenDomain.CLASS_RUNTIME,
                "new_object_array",
                allocationKey);
        builder.append("jarray ")
                .append(symbol)
                .append("(JNIEnv* env, int32_t length) {\n")
                .append("    if (length < 0) {\n")
                .append("        j2ll_throw_new(env, \"java/lang/NegativeArraySizeException\", \"negative object array length\");\n")
                .append("        return NULL;\n")
                .append("    }\n")
                .append("    jclass component = (*env)->FindClass(env, \"")
                .append(escapeCString(parts.jniName()))
                .append("\");\n")
                .append("    if (component == NULL) return NULL;\n")
                .append("    jobjectArray array = (*env)->NewObjectArray(env, (jsize)length, component, NULL);\n")
                .append("    (*env)->DeleteLocalRef(env, component);\n")
                .append("    return (jarray)array;\n")
                .append("}\n\n");
    }

    private static void appendTypeCheck(
            StringBuilder builder,
            RuntimeTokenMapper runtimeTokens,
            String typeCheckKey,
            ClassParts parts) {
        boolean checkcast = typeCheckKey.startsWith("checkcast:");
        String operation = checkcast ? "checkcast" : "instanceof";
        String symbol = runtimeTokens.helperSymbol(
                RuntimeTokenDomain.CLASS_RUNTIME,
                operation,
                typeCheckKey);
        builder.append(checkcast ? "jobject " : "int32_t ")
                .append(symbol)
                .append("(JNIEnv* env, jobject value) {\n");
        if (checkcast) {
            builder.append("    if (value == NULL) return NULL;\n");
        } else {
            builder.append("    if (value == NULL) return 0;\n");
        }
        builder.append("    jclass target = (*env)->FindClass(env, \"")
                .append(escapeCString(parts.jniName()))
                .append("\");\n")
                .append(checkcast
                        ? "    if (target == NULL) return NULL;\n"
                        : "    if (target == NULL) return 0;\n")
                .append("    jboolean matched = (*env)->IsInstanceOf(env, value, target);\n")
                .append("    (*env)->DeleteLocalRef(env, target);\n");
        if (checkcast) {
            builder.append("    if (matched != JNI_TRUE) {\n")
                    .append("        j2ll_throw_new(env, \"java/lang/ClassCastException\", \"j2ll checkcast failed\");\n")
                    .append("        return NULL;\n")
                    .append("    }\n")
                    .append("    return value;\n");
        } else {
            builder.append("    return matched == JNI_TRUE ? 1 : 0;\n");
        }
        builder.append("}\n\n");
    }

    private static void appendClassObject(
            StringBuilder builder,
            RuntimeTokenMapper runtimeTokens,
            String classObjectKey,
            ClassParts parts) {
        String symbol = runtimeTokens.helperSymbol(
                RuntimeTokenDomain.CLASS_OBJECT,
                "class_object",
                parts.identity());
        builder.append("jclass ")
                .append(symbol)
                .append("(JNIEnv* env) {\n")
                .append("    return (*env)->FindClass(env, \"")
                .append(escapeCString(parts.jniName()))
                .append("\");\n")
                .append("}\n\n");
    }

    private static String runtimeHelperBodies() {
        return """
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

                """;
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

    private static String escapeCString(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record ClassParts(String identity, String jniName) {}
}
