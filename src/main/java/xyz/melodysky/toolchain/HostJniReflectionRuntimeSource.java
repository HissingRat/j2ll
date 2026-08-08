package xyz.melodysky.toolchain;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.runtime.RuntimeTokenDomain;
import xyz.melodysky.runtime.RuntimeTokenMapper;
import xyz.melodysky.runtime.jni.RuntimeLocalAbiDomain;

/**
 * Emits reflection metadata at its concrete use sites.
 *
 * <p>There is deliberately no token-to-owner/member table. A metadata lookup
 * has one hash-only helper whose owner, member and descriptor only exist in
 * call-local decoded scratch. Generic operations on already-created reflection
 * objects remain fixed runtime helpers and carry no member metadata.</p>
 */
final class HostJniReflectionRuntimeSource {
    private HostJniReflectionRuntimeSource() {}

    static boolean emitsVarHandleDependentSupport(
            List<HostJniCSourceGenerator.Binding> bindings) {
        Set<String> helpers = runtimeHelpers(bindings);
        return helpers.contains("j2ll_rt_unsafe_get")
                || helpers.contains("j2ll_rt_unsafe_get_volatile");
    }

    static void append(
            StringBuilder builder,
            List<HostJniCSourceGenerator.Binding> bindings,
            RuntimeTokenMapper runtimeTokens) {
        TreeSet<String> methodKeys = metadataKeys(bindings, "method:");
        TreeSet<String> constructorKeys = metadataKeys(
                bindings,
                "constructor:");
        TreeSet<String> fieldKeys = metadataKeys(bindings, "field:");
        TreeSet<String> classKeys = metadataKeys(bindings, "class:");
        Set<String> helpers = runtimeHelpers(bindings);
        boolean needsReflection = !methodKeys.isEmpty()
                || !constructorKeys.isEmpty()
                || !fieldKeys.isEmpty()
                || !classKeys.isEmpty()
                || helpers.stream().anyMatch(symbol ->
                        symbol.startsWith("j2ll_rt_reflect_")
                                || symbol.startsWith("j2ll_rt_unsafe_"));
        if (!needsReflection) {
            return;
        }
        appendCommon(
                builder,
                !classKeys.isEmpty(),
                !methodKeys.isEmpty() || !constructorKeys.isEmpty());
        for (String key : runtimeTokens.physicalOrder(
                RuntimeTokenDomain.REFLECTION_METHOD,
                List.copyOf(methodKeys),
                value -> value)) {
            appendMethodLookup(builder, runtimeTokens, key, false);
        }
        for (String key : runtimeTokens.physicalOrder(
                RuntimeTokenDomain.REFLECTION_METHOD,
                List.copyOf(constructorKeys),
                value -> value)) {
            appendMethodLookup(builder, runtimeTokens, key, true);
        }
        for (String key : runtimeTokens.physicalOrder(
                RuntimeTokenDomain.REFLECTION_FIELD,
                List.copyOf(fieldKeys),
                value -> value)) {
            appendFieldLookup(builder, runtimeTokens, key);
        }
        appendReflectionObjectOperations(builder, helpers);
        appendUnsafeOperations(builder, helpers, fieldKeys, runtimeTokens);
    }

    static String helperSymbol(
            RuntimeTokenMapper runtimeTokens,
            String metadataKey) {
        if (metadataKey.startsWith("method:")) {
            return runtimeTokens.helperSymbol(
                    RuntimeTokenDomain.REFLECTION_METHOD,
                    localAbiOperation(metadataKey),
                    metadataKey);
        }
        if (metadataKey.startsWith("constructor:")) {
            return runtimeTokens.helperSymbol(
                    RuntimeTokenDomain.REFLECTION_METHOD,
                    localAbiOperation(metadataKey),
                    metadataKey);
        }
        if (metadataKey.startsWith("field:")) {
            return runtimeTokens.helperSymbol(
                    RuntimeTokenDomain.REFLECTION_FIELD,
                    localAbiOperation(metadataKey),
                    metadataKey);
        }
        throw new IllegalArgumentException(
                "unsupported reflection metadata key " + metadataKey);
    }

    private static String localAbiOperation(String metadataKey) {
        if (metadataKey.startsWith("method:")) {
            return "reflection_lookup_method";
        }
        if (metadataKey.startsWith("constructor:")) {
            return "reflection_lookup_constructor";
        }
        if (metadataKey.startsWith("field:")) {
            return "reflection_lookup_field";
        }
        throw new IllegalArgumentException(
                "unsupported reflection metadata key " + metadataKey);
    }

    private static TreeSet<String> metadataKeys(
            List<HostJniCSourceGenerator.Binding> bindings,
            String prefix) {
        TreeSet<String> keys = new TreeSet<>();
        bindings.stream()
                .filter(binding ->
                        binding.path()
                                == NativeImplementationPath.LLVM_NATIVE_PATH)
                .flatMap(binding -> binding.runtimeMetadataKeys().stream())
                .filter(key -> key.startsWith(prefix))
                .forEach(keys::add);
        return keys;
    }

    private static Set<String> runtimeHelpers(
            List<HostJniCSourceGenerator.Binding> bindings) {
        TreeSet<String> helpers = new TreeSet<>();
        bindings.stream()
                .filter(binding ->
                        binding.path()
                                == NativeImplementationPath.LLVM_NATIVE_PATH)
                .flatMap(binding -> binding.implementationIrMethod().stream())
                .flatMap(method -> method.blocks().stream())
                .flatMap(block -> block.instructions().stream())
                .filter(instruction ->
                        instruction.opcode() == IrOpcode.CALL_RUNTIME_HELPER)
                .map(IrInstruction::symbol)
                .flatMap(java.util.Optional::stream)
                .map(HostJniReflectionRuntimeSource::baseSymbol)
                .forEach(helpers::add);
        return helpers;
    }

    private static String baseSymbol(String symbol) {
        int separator = symbol.indexOf('|');
        return separator < 0 ? symbol : symbol.substring(0, separator);
    }

    private static void appendCommon(
            StringBuilder builder,
            boolean classForName,
            boolean parameterArray) {
        if (classForName) {
            builder.append("""
                static jclass j2ll_class_for_name_with_init(
                        JNIEnv* env,
                        const char* internal_name,
                        const char* dotted_name,
                        int initialize) {
                    /*
                     * FindClass executes in the registered native caller's
                     * defining-loader context. It is intentionally not
                     * replaced with the thread context class loader.
                     */
                    jclass target = (*env)->FindClass(env, internal_name);
                    if (target == NULL || !initialize) return target;
                    jclass cls = (*env)->FindClass(env, "java/lang/Class");
                    if (cls == NULL) {
                        (*env)->DeleteLocalRef(env, target);
                        return NULL;
                    }
                    jmethodID method = (*env)->GetStaticMethodID(
                            env, cls, "forName",
                            "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;");
                    jmethodID get_loader = (*env)->GetMethodID(
                            env, cls, "getClassLoader",
                            "()Ljava/lang/ClassLoader;");
                    jstring name = method == NULL
                            ? NULL
                            : (*env)->NewStringUTF(env, dotted_name);
                    if (method == NULL || get_loader == NULL || name == NULL) {
                        (*env)->DeleteLocalRef(env, cls);
                        (*env)->DeleteLocalRef(env, target);
                        return NULL;
                    }
                    jobject loader = (*env)->CallObjectMethod(
                            env, target, get_loader);
                    if ((*env)->ExceptionCheck(env)) {
                        (*env)->DeleteLocalRef(env, cls);
                        (*env)->DeleteLocalRef(env, target);
                        (*env)->DeleteLocalRef(env, name);
                        if (loader != NULL) (*env)->DeleteLocalRef(env, loader);
                        return NULL;
                    }
                    jclass result = (jclass)(*env)->CallStaticObjectMethod(
                            env, cls, method, name,
                            initialize ? JNI_TRUE : JNI_FALSE, loader);
                    (*env)->DeleteLocalRef(env, cls);
                    (*env)->DeleteLocalRef(env, target);
                    (*env)->DeleteLocalRef(env, name);
                    if (loader != NULL) (*env)->DeleteLocalRef(env, loader);
                    return result;
                }

                """);
        }
        if (parameterArray) {
            builder.append("""

                static jobjectArray j2ll_parameter_array_for_descriptor(
                        JNIEnv* env, const char* descriptor, jobject loader) {
                    jclass method_type_class =
                            (*env)->FindClass(env, "java/lang/invoke/MethodType");
                    if (method_type_class == NULL) return NULL;
                    jmethodID parse = (*env)->GetStaticMethodID(
                            env, method_type_class, "fromMethodDescriptorString",
                            "(Ljava/lang/String;Ljava/lang/ClassLoader;)Ljava/lang/invoke/MethodType;");
                    jmethodID parameters = (*env)->GetMethodID(
                            env, method_type_class, "parameterArray",
                            "()[Ljava/lang/Class;");
                    if (parse == NULL || parameters == NULL) {
                        (*env)->DeleteLocalRef(env, method_type_class);
                        return NULL;
                    }
                    jstring value = (*env)->NewStringUTF(env, descriptor);
                    jobject method_type = value == NULL
                            ? NULL : (*env)->CallStaticObjectMethod(
                                    env, method_type_class, parse, value, loader);
                    if (value != NULL) (*env)->DeleteLocalRef(env, value);
                    if (method_type == NULL) {
                        (*env)->DeleteLocalRef(env, method_type_class);
                        return NULL;
                    }
                    jobjectArray result = (jobjectArray)(*env)->CallObjectMethod(
                            env, method_type, parameters);
                    (*env)->DeleteLocalRef(env, method_type);
                    (*env)->DeleteLocalRef(env, method_type_class);
                    return result;
                }

                """);
        }
    }

    private static void appendMethodLookup(
            StringBuilder builder,
            RuntimeTokenMapper runtimeTokens,
            String key,
            boolean constructor) {
        MethodParts parts = MethodParts.parse(
                key.substring(key.indexOf(':') + 1));
        String symbol = helperSymbol(runtimeTokens, key);
        HostJniLocalAbiSource.Emission localAbi =
                HostJniLocalAbiSource.emit(
                        runtimeTokens,
                        RuntimeLocalAbiDomain.REFLECTION,
                        localAbiOperation(key),
                        key,
                        List.of(new HostJniLocalAbiSource.Parameter(
                                "JNIEnv*",
                                "env")));
        builder.append("jobject ")
                .append(symbol)
                .append('(')
                .append(localAbi.parameterDeclarations())
                .append(") {\n")
                .append("    jclass owner = (*env)->FindClass(env, \"")
                .append(CSourceEscaper.stringContents(parts.owner()))
                .append("\");\n")
                .append("    if (owner == NULL) return NULL;\n")
                .append("    jclass class_class = (*env)->FindClass(env, \"java/lang/Class\");\n")
                .append("    if (class_class == NULL) { (*env)->DeleteLocalRef(env, owner); return NULL; }\n")
                .append("    jmethodID get_loader = (*env)->GetMethodID(env, class_class, \"getClassLoader\", \"()Ljava/lang/ClassLoader;\");\n")
                .append("    jobject loader = get_loader == NULL ? NULL : (*env)->CallObjectMethod(env, owner, get_loader);\n")
                .append("    if (get_loader == NULL || (*env)->ExceptionCheck(env)) { (*env)->DeleteLocalRef(env, class_class); (*env)->DeleteLocalRef(env, owner); if (loader != NULL) (*env)->DeleteLocalRef(env, loader); return NULL; }\n")
                .append("    jobjectArray parameters = j2ll_parameter_array_for_descriptor(env, \"")
                .append(CSourceEscaper.stringContents(
                        normalizedMethodDescriptor(parts.descriptor())))
                .append("\", loader);\n")
                .append("    if (loader != NULL) (*env)->DeleteLocalRef(env, loader);\n")
                .append("    if (parameters == NULL) { (*env)->DeleteLocalRef(env, class_class); (*env)->DeleteLocalRef(env, owner); return NULL; }\n");
        if (constructor) {
            builder.append("""
                    jmethodID lookup = (*env)->GetMethodID(
                            env, class_class, "getDeclaredConstructor",
                            "([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;");
                    (*env)->DeleteLocalRef(env, class_class);
                    jobject result = lookup == NULL ? NULL : (*env)->CallObjectMethod(
                            env, owner, lookup, parameters);
                    """);
        } else {
            builder.append("    jmethodID lookup = (*env)->GetMethodID(\n")
                    .append("            env, class_class, \"getDeclaredMethod\",\n")
                    .append("            \"(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;\");\n")
                    .append("    (*env)->DeleteLocalRef(env, class_class);\n")
                    .append("    jstring name = lookup == NULL ? NULL : (*env)->NewStringUTF(env, \"")
                    .append(CSourceEscaper.stringContents(parts.name()))
                    .append("\");\n")
                    .append("    jobject result = name == NULL ? NULL : (*env)->CallObjectMethod(\n")
                    .append("            env, owner, lookup, name, parameters);\n")
                    .append("    if (name != NULL) (*env)->DeleteLocalRef(env, name);\n");
        }
        builder.append("""
                    (*env)->DeleteLocalRef(env, owner);
                    (*env)->DeleteLocalRef(env, parameters);
                    return result;
                }

                """);
    }

    private static String normalizedMethodDescriptor(String descriptor) {
        return descriptor.endsWith(")") ? descriptor + "V" : descriptor;
    }

    private static void appendFieldLookup(
            StringBuilder builder,
            RuntimeTokenMapper runtimeTokens,
            String key) {
        FieldParts parts = FieldParts.parse(
                key.substring("field:".length()));
        HostJniLocalAbiSource.Emission localAbi =
                HostJniLocalAbiSource.emit(
                        runtimeTokens,
                        RuntimeLocalAbiDomain.REFLECTION,
                        localAbiOperation(key),
                        key,
                        List.of(new HostJniLocalAbiSource.Parameter(
                                "JNIEnv*",
                                "env")));
        builder.append("jobject ")
                .append(helperSymbol(runtimeTokens, key))
                .append('(')
                .append(localAbi.parameterDeclarations())
                .append(") {\n")
                .append("    jclass owner = (*env)->FindClass(env, \"")
                .append(CSourceEscaper.stringContents(parts.owner()))
                .append("\");\n")
                .append("    if (owner == NULL) return NULL;\n")
                .append("    jclass cls = (*env)->FindClass(env, \"java/lang/Class\");\n")
                .append("    if (cls == NULL) { (*env)->DeleteLocalRef(env, owner); return NULL; }\n")
                .append("    jmethodID lookup = (*env)->GetMethodID(\n")
                .append("            env, cls, \"getDeclaredField\",\n")
                .append("            \"(Ljava/lang/String;)Ljava/lang/reflect/Field;\");\n")
                .append("    (*env)->DeleteLocalRef(env, cls);\n")
                .append("    jstring name = lookup == NULL ? NULL : (*env)->NewStringUTF(env, \"")
                .append(CSourceEscaper.stringContents(parts.name()))
                .append("\");\n")
                .append("    jobject result = name == NULL ? NULL : (*env)->CallObjectMethod(env, owner, lookup, name);\n")
                .append("    (*env)->DeleteLocalRef(env, owner);\n")
                .append("    if (name != NULL) (*env)->DeleteLocalRef(env, name);\n")
                .append("    return result;\n")
                .append("}\n\n");
    }

    private static void appendReflectionObjectOperations(
            StringBuilder builder,
            Set<String> helpers) {
        if (helpers.contains("j2ll_rt_reflect_invoke")) {
            builder.append("""
                    jobject j2ll_rt_reflect_invoke(
                            JNIEnv* env, jobject method, jobject target, jobject args) {
                        if (method == NULL) {
                            j2ll_throw_new(env, "java/lang/NullPointerException", "reflection receiver is null");
                            return NULL;
                        }
                        jclass cls = (*env)->FindClass(env, "java/lang/reflect/Method");
                        if (cls == NULL) return NULL;
                        jmethodID invoke = (*env)->GetMethodID(
                                env, cls, "invoke",
                                "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
                        (*env)->DeleteLocalRef(env, cls);
                        return invoke == NULL ? NULL : (*env)->CallObjectMethod(
                                env, method, invoke, target, args);
                    }

                    """);
        }
        if (helpers.contains("j2ll_rt_reflect_new_instance")) {
            builder.append("""
                    jobject j2ll_rt_reflect_new_instance(
                            JNIEnv* env, jobject constructor, jobject args) {
                        if (constructor == NULL) {
                            j2ll_throw_new(env, "java/lang/NullPointerException", "reflection receiver is null");
                            return NULL;
                        }
                        jclass cls = (*env)->FindClass(env, "java/lang/reflect/Constructor");
                        if (cls == NULL) return NULL;
                        jmethodID method = (*env)->GetMethodID(
                                env, cls, "newInstance",
                                "([Ljava/lang/Object;)Ljava/lang/Object;");
                        (*env)->DeleteLocalRef(env, cls);
                        return method == NULL ? NULL : (*env)->CallObjectMethod(
                                env, constructor, method, args);
                    }

                    """);
        }
        if (helpers.contains("j2ll_rt_reflect_set_accessible")) {
            builder.append("""
                    void j2ll_rt_reflect_set_accessible(
                            JNIEnv* env, jobject value, int32_t flag) {
                        if (value == NULL) {
                            j2ll_throw_new(env, "java/lang/NullPointerException", "reflection receiver is null");
                            return;
                        }
                        jclass cls = (*env)->FindClass(env, "java/lang/reflect/AccessibleObject");
                        if (cls == NULL) return;
                        jmethodID method = (*env)->GetMethodID(env, cls, "setAccessible", "(Z)V");
                        (*env)->DeleteLocalRef(env, cls);
                        if (method != NULL) (*env)->CallVoidMethod(
                                env, value, method, flag ? JNI_TRUE : JNI_FALSE);
                    }

                    """);
        }
        appendFieldOperation(builder, helpers, "get", "Object", "jobject",
                "jobject", "NULL", false);
        appendFieldOperation(builder, helpers, "set", "Object", "void",
                "jobject", "", true);
        appendFieldOperation(builder, helpers, "get_int", "Int", "int32_t",
                "jint", "0", false);
        appendFieldOperation(builder, helpers, "set_int", "Int", "void",
                "jint", "", true);
        appendFieldOperation(builder, helpers, "get_boolean", "Boolean",
                "int32_t", "jboolean", "0", false);
        appendFieldOperation(builder, helpers, "set_boolean", "Boolean",
                "void", "jboolean", "", true);
        appendFieldOperation(builder, helpers, "get_long", "Long", "int64_t",
                "jlong", "0", false);
        appendFieldOperation(builder, helpers, "set_long", "Long", "void",
                "jlong", "", true);
        appendFieldOperation(builder, helpers, "get_double", "Double",
                "double", "jdouble", "0.0", false);
        appendFieldOperation(builder, helpers, "set_double", "Double",
                "void", "jdouble", "", true);
    }

    private static void appendFieldOperation(
            StringBuilder builder,
            Set<String> helpers,
            String operation,
            String jniSuffix,
            String cReturn,
            String jniType,
            String defaultValue,
            boolean setter) {
        String symbol = "j2ll_rt_reflect_field_" + operation;
        if (!helpers.contains(symbol)) {
            return;
        }
        builder.append(cReturn)
                .append(' ')
                .append(symbol)
                .append("(JNIEnv* env, jobject field, jobject target");
        if (setter) {
            builder.append(", ").append(cReturn.equals("void")
                    ? switch (jniType) {
                        case "jobject" -> "jobject";
                        case "jlong" -> "int64_t";
                        case "jdouble" -> "double";
                        default -> "int32_t";
                    }
                    : cReturn).append(" value");
        }
        builder.append(") {\n")
                .append("    if (field == NULL) {\n")
                .append("        j2ll_throw_new(env, \"java/lang/NullPointerException\", \"reflection receiver is null\");\n")
                .append(setter ? "        return;\n" : "        return " + defaultValue + ";\n")
                .append("    }\n")
                .append("    jclass cls = (*env)->FindClass(env, \"java/lang/reflect/Field\");\n")
                .append(setter ? "    if (cls == NULL) return;\n"
                        : "    if (cls == NULL) return " + defaultValue + ";\n");
        String descriptor = setter
                ? "(Ljava/lang/Object;" + descriptorFor(jniType) + ")V"
                : "(Ljava/lang/Object;)" + descriptorFor(jniType);
        builder.append("    jmethodID method = (*env)->GetMethodID(env, cls, \"")
                .append(setter ? "set" : "get")
                .append(jniSuffix)
                .append("\", \"")
                .append(descriptor)
                .append("\");\n")
                .append("    (*env)->DeleteLocalRef(env, cls);\n");
        if (setter) {
            String value = switch (jniType) {
                case "jboolean" -> "value ? JNI_TRUE : JNI_FALSE";
                case "jint" -> "(jint)value";
                case "jlong" -> "(jlong)value";
                case "jdouble" -> "(jdouble)value";
                default -> "value";
            };
            builder.append("    if (method != NULL) (*env)->CallVoidMethod(env, field, method, target, ")
                    .append(value)
                    .append(");\n}\n\n");
        } else {
            String call = "(*env)->Call" + jniSuffix
                    + "Method(env, field, method, target)";
            String result = switch (jniType) {
                case "jboolean" -> call + " == JNI_FALSE ? 0 : 1";
                case "jint" -> "(int32_t)" + call;
                case "jlong" -> "(int64_t)" + call;
                case "jdouble" -> "(double)" + call;
                default -> call;
            };
            builder.append("    return method == NULL ? ")
                    .append(defaultValue)
                    .append(" : ")
                    .append(result)
                    .append(";\n}\n\n");
        }
    }

    private static String descriptorFor(String jniType) {
        return switch (jniType) {
            case "jobject" -> "Ljava/lang/Object;";
            case "jboolean" -> "Z";
            case "jint" -> "I";
            case "jlong" -> "J";
            case "jdouble" -> "D";
            default -> throw new IllegalArgumentException(jniType);
        };
    }

    private static void appendUnsafeOperations(
            StringBuilder builder,
            Set<String> helpers,
            Set<String> fieldKeys,
            RuntimeTokenMapper runtimeTokens) {
        boolean needsOffset = helpers.contains("j2ll_rt_unsafe_object_field_offset")
                || helpers.contains("j2ll_rt_unsafe_static_field_offset");
        boolean needsIntAccess = helpers.contains("j2ll_rt_unsafe_get_int")
                || helpers.contains("j2ll_rt_unsafe_put_int")
                || helpers.contains("j2ll_rt_unsafe_compare_and_swap_int");
        boolean needsReferenceGet = helpers.contains("j2ll_rt_unsafe_get")
                || helpers.contains("j2ll_rt_unsafe_get_volatile");
        if (!needsOffset && !needsIntAccess
                && !needsReferenceGet
                && !helpers.contains("j2ll_rt_unsafe_allocate_instance")) {
            return;
        }
        if (needsOffset || needsIntAccess) {
            appendUnsafeBindingHelpers(
                    builder, fieldKeys, runtimeTokens);
        }
        if (needsOffset) {
            appendUnsafeOffset(builder, fieldKeys, runtimeTokens);
        }
        if (needsIntAccess) {
            appendUnsafeIntResolver(builder, fieldKeys, runtimeTokens);
        }
        if (helpers.contains("j2ll_rt_unsafe_get_int")) {
            builder.append("""
                    int32_t j2ll_rt_unsafe_get_int(JNIEnv* env, jobject target, int64_t token) {
                        jclass cls = NULL;
                        jfieldID field = j2ll_unsafe_int_field_id(env, target, token, &cls);
                        if (field == NULL) return 0;
                        jint result = (*env)->GetIntField(env, target, field);
                        (*env)->DeleteLocalRef(env, cls);
                        return (int32_t)result;
                    }

                    """);
        }
        if (helpers.contains("j2ll_rt_unsafe_put_int")) {
            builder.append("""
                    void j2ll_rt_unsafe_put_int(
                            JNIEnv* env, jobject target, int64_t token, int32_t value) {
                        jclass cls = NULL;
                        jfieldID field = j2ll_unsafe_int_field_id(env, target, token, &cls);
                        if (field == NULL) return;
                        (*env)->SetIntField(env, target, field, (jint)value);
                        (*env)->DeleteLocalRef(env, cls);
                    }

                    """);
        }
        if (helpers.contains("j2ll_rt_unsafe_compare_and_swap_int")) {
            builder.append("""
                    int32_t j2ll_rt_unsafe_compare_and_swap_int(
                            JNIEnv* env, jobject target, int64_t token,
                            int32_t expected, int32_t update) {
                        jclass cls = NULL;
                        jfieldID field = j2ll_unsafe_int_field_id(env, target, token, &cls);
                        if (field == NULL) return 0;
                        if ((*env)->MonitorEnter(env, target) != JNI_OK) {
                            (*env)->DeleteLocalRef(env, cls);
                            return 0;
                        }
                        jint current = (*env)->GetIntField(env, target, field);
                        int32_t success = 0;
                        if (!(*env)->ExceptionCheck(env) && current == (jint)expected) {
                            (*env)->SetIntField(env, target, field, (jint)update);
                            success = !(*env)->ExceptionCheck(env);
                        }
                        if ((*env)->MonitorExit(env, target) != JNI_OK) success = 0;
                        (*env)->DeleteLocalRef(env, cls);
                        return success;
                    }

                    """);
        }
        if (helpers.contains("j2ll_rt_unsafe_allocate_instance")) {
            builder.append("""
                    jobject j2ll_rt_unsafe_allocate_instance(JNIEnv* env, jclass cls) {
                        if (cls == NULL) {
                            j2ll_throw_new(env, "java/lang/NullPointerException", "class is null");
                            return NULL;
                        }
                        return (*env)->AllocObject(env, cls);
                    }

                    """);
        }
        if (helpers.contains("j2ll_rt_unsafe_get")) {
            appendUnsafeReferenceGet(
                    builder,
                    "j2ll_rt_unsafe_get",
                    "GET");
        }
        if (helpers.contains("j2ll_rt_unsafe_get_volatile")) {
            appendUnsafeReferenceGet(
                    builder,
                    "j2ll_rt_unsafe_get_volatile",
                    "GET_VOLATILE");
        }
    }

    private static void appendUnsafeReferenceGet(
            StringBuilder builder,
            String symbol,
            String accessMode) {
        builder.append("jobject ")
                .append(symbol)
                .append("(JNIEnv* env, jobject handle, jobject target) {\n")
                .append("    jobject method = j2ll_var_handle_method_handle(env, handle, \"")
                .append(accessMode)
                .append("\");\n")
                .append("    if (method == NULL) return NULL;\n")
                .append("    jobjectArray args = j2ll_var_handle_args(env, target, 0);\n")
                .append("    if (args == NULL) { (*env)->DeleteLocalRef(env, method); return NULL; }\n")
                .append("    jobject result = j2ll_invoke_method_handle_with_args(env, method, args);\n")
                .append("    (*env)->DeleteLocalRef(env, args);\n")
                .append("    (*env)->DeleteLocalRef(env, method);\n")
                .append("    return result;\n")
                .append("}\n\n");
    }

    private static void appendUnsafeBindingHelpers(
            StringBuilder builder,
            Set<String> fieldKeys,
            RuntimeTokenMapper runtimeTokens) {
        for (String key : fieldKeys) {
            FieldParts parts = FieldParts.parse(
                    key.substring("field:".length()));
            String match = runtimeTokens.helperSymbol(
                    RuntimeTokenDomain.REFLECTION_FIELD,
                    "unsafe_field_match",
                    key);
            String resolve = runtimeTokens.helperSymbol(
                    RuntimeTokenDomain.REFLECTION_FIELD,
                    "unsafe_int_field",
                    key);
            builder.append("static int ")
                    .append(match)
                    .append("(JNIEnv* env, jobject field) {\n")
                    .append("    jclass field_cls = (*env)->FindClass(env, \"java/lang/reflect/Field\");\n")
                    .append("    if (field_cls == NULL) return 0;\n")
                    .append("    jmethodID owner_method = (*env)->GetMethodID(env, field_cls, \"getDeclaringClass\", \"()Ljava/lang/Class;\");\n")
                    .append("    jmethodID name_method = (*env)->GetMethodID(env, field_cls, \"getName\", \"()Ljava/lang/String;\");\n")
                    .append("    (*env)->DeleteLocalRef(env, field_cls);\n")
                    .append("    if (owner_method == NULL || name_method == NULL) return 0;\n")
                    .append("    jobject declared = (*env)->CallObjectMethod(env, field, owner_method);\n")
                    .append("    jclass expected = (*env)->FindClass(env, \"")
                    .append(CSourceEscaper.stringContents(parts.owner()))
                    .append("\");\n")
                    .append("    jstring actual_name = (jstring)(*env)->CallObjectMethod(env, field, name_method);\n")
                    .append("    jstring expected_name = (*env)->NewStringUTF(env, \"")
                    .append(CSourceEscaper.stringContents(parts.name()))
                    .append("\");\n")
                    .append("    int result = declared != NULL && expected != NULL && actual_name != NULL && expected_name != NULL\n")
                    .append("            && (*env)->IsSameObject(env, declared, expected)\n")
                    .append("            && (*env)->CallBooleanMethod(env, actual_name,\n")
                    .append("                    (*env)->GetMethodID(env, (*env)->FindClass(env, \"java/lang/String\"), \"equals\", \"(Ljava/lang/Object;)Z\"), expected_name);\n")
                    .append("    if (declared != NULL) (*env)->DeleteLocalRef(env, declared);\n")
                    .append("    if (expected != NULL) (*env)->DeleteLocalRef(env, expected);\n")
                    .append("    if (actual_name != NULL) (*env)->DeleteLocalRef(env, actual_name);\n")
                    .append("    if (expected_name != NULL) (*env)->DeleteLocalRef(env, expected_name);\n")
                    .append("    return result;\n}\n\n")
                    .append("static jfieldID ")
                    .append(resolve)
                    .append("(JNIEnv* env, jobject target, jclass* cls) {\n")
                    .append("    if (target == NULL) return NULL;\n")
                    .append("    *cls = (*env)->FindClass(env, \"")
                    .append(CSourceEscaper.stringContents(parts.owner()))
                    .append("\");\n")
                    .append("    return *cls == NULL ? NULL : (*env)->GetFieldID(env, *cls, \"")
                    .append(CSourceEscaper.stringContents(parts.name()))
                    .append("\", \"I\");\n}\n\n");
        }
    }

    private static void appendUnsafeOffset(
            StringBuilder builder,
            Set<String> fieldKeys,
            RuntimeTokenMapper runtimeTokens) {
        builder.append("""
                int64_t j2ll_rt_unsafe_object_field_offset(JNIEnv* env, jobject field) {
                    if (field == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "field is null");
                        return 0;
                    }
                """);
        for (String key : fieldKeys) {
            builder.append("    if (")
                    .append(runtimeTokens.helperSymbol(
                            RuntimeTokenDomain.REFLECTION_FIELD,
                            "unsafe_field_match",
                            key))
                    .append("(env, field)) return ")
                    .append(runtimeTokens.token(
                            RuntimeTokenDomain.REFLECTION_FIELD,
                            "unsafe-offset:" + key))
                    .append("LL;\n");
        }
        builder.append("""
                    j2ll_throw_new(env, "java/lang/IllegalArgumentException", "unsupported field");
                    return 0;
                }

                int64_t j2ll_rt_unsafe_static_field_offset(JNIEnv* env, jobject field) {
                    return j2ll_rt_unsafe_object_field_offset(env, field);
                }

                """);
    }

    private static void appendUnsafeIntResolver(
            StringBuilder builder,
            Set<String> fieldKeys,
            RuntimeTokenMapper runtimeTokens) {
        builder.append("""
                static jfieldID j2ll_unsafe_int_field_id(
                        JNIEnv* env, jobject target, int64_t token, jclass* cls) {
                    if (target == NULL) {
                        j2ll_throw_new(env, "java/lang/NullPointerException", "target is null");
                        return NULL;
                    }
                """);
        for (String key : fieldKeys) {
            builder.append("    if (token == ")
                    .append(runtimeTokens.token(
                            RuntimeTokenDomain.REFLECTION_FIELD,
                            "unsafe-offset:" + key))
                    .append("LL) return ")
                    .append(runtimeTokens.helperSymbol(
                            RuntimeTokenDomain.REFLECTION_FIELD,
                            "unsafe_int_field",
                            key))
                    .append("(env, target, cls);\n");
        }
        builder.append("""
                    j2ll_throw_new(env, "java/lang/IllegalArgumentException", "unknown field offset");
                    return NULL;
                }

                """);
    }

    private record MethodParts(
            String owner,
            String name,
            String descriptor) {
        static MethodParts parse(String key) {
            int ownerEnd = key.indexOf('#');
            int descriptorStart = key.indexOf('!');
            if (ownerEnd < 1 || descriptorStart <= ownerEnd + 1) {
                throw new IllegalArgumentException(
                        "invalid method metadata key " + key);
            }
            return new MethodParts(
                    key.substring(0, ownerEnd),
                    key.substring(ownerEnd + 1, descriptorStart),
                    key.substring(descriptorStart + 1));
        }
    }

    private record FieldParts(String owner, String name) {
        static FieldParts parse(String key) {
            int ownerEnd = key.indexOf('#');
            if (ownerEnd < 1 || ownerEnd == key.length() - 1) {
                throw new IllegalArgumentException(
                        "invalid field metadata key " + key);
            }
            return new FieldParts(
                    key.substring(0, ownerEnd),
                    key.substring(ownerEnd + 1));
        }
    }
}
