package xyz.melodysky.toolchain;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.runtime.RuntimeTokenDomain;
import xyz.melodysky.runtime.RuntimeTokenMapper;

final class HostJniLambdaRuntimeSource {
    private HostJniLambdaRuntimeSource() {}

    static void append(StringBuilder builder, List<HostJniCSourceGenerator.Binding> bindings) {
        append(builder, bindings, RuntimeTokenMapper.compatibility());
    }

    static void append(
            StringBuilder builder,
            List<HostJniCSourceGenerator.Binding> bindings,
            RuntimeTokenMapper runtimeTokens) {
        Map<String, String[]> lambdaSpecs = new TreeMap<>();
        for (HostJniCSourceGenerator.Binding binding : bindings) {
            if (binding.path() != NativeImplementationPath.LLVM_NATIVE_PATH || binding.templateIrMethod().isEmpty()) {
                continue;
            }
            binding.templateIrMethod().orElseThrow().blocks().stream()
                    .flatMap(block -> block.instructions().stream())
                    .filter(instruction -> instruction.opcode() == IrOpcode.CALL_RUNTIME_HELPER)
                    .flatMap(instruction -> instruction.symbol().stream())
                    .filter(symbol -> symbol.startsWith("j2ll_rt_lambda_new|lambda:"))
                    .forEach(symbol -> {
                        String encoded = symbol.substring("j2ll_rt_lambda_new|lambda:".length());
                        String spec = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
                        String[] fields = spec.split("\n", -1);
                        if (fields.length == 9) {
                            lambdaSpecs.putIfAbsent("lambda:" + encoded, fields);
                        }
                    });
        }
        if (lambdaSpecs.isEmpty()) {
            return;
        }
        builder.append("""
                typedef struct {
                    const char* caller_owner;
                    const char* invoked_name;
                    const char* invoked_desc;
                    const char* sam_desc;
                    int ref_kind;
                    const char* impl_owner;
                    const char* impl_name;
                    const char* impl_desc;
                    const char* instantiated_desc;
                } j2ll_lambda_entry;

                static jobject j2ll_method_type_from_descriptor(JNIEnv* env, const char* descriptor, jobject loader) {
                    jclass method_type_class = (*env)->FindClass(env, "java/lang/invoke/MethodType");
                    if (method_type_class == NULL) {
                        return NULL;
                    }
                    jmethodID from_descriptor = (*env)->GetStaticMethodID(
                            env,
                            method_type_class,
                            "fromMethodDescriptorString",
                            "(Ljava/lang/String;Ljava/lang/ClassLoader;)Ljava/lang/invoke/MethodType;");
                    if (from_descriptor == NULL) {
                        return NULL;
                    }
                    jstring descriptor_string = (*env)->NewStringUTF(env, descriptor);
                    if (descriptor_string == NULL) {
                        return NULL;
                    }
                    return (*env)->CallStaticObjectMethod(env, method_type_class, from_descriptor, descriptor_string, loader);
                }

                static jobject j2ll_lambda_impl_handle(
                        JNIEnv* env,
                        jobject lookup,
                        jclass owner_class,
                        const j2ll_lambda_entry* entry,
                        jobject impl_type) {
                    jclass lookup_class = (*env)->FindClass(env, "java/lang/invoke/MethodHandles$Lookup");
                    if (lookup_class == NULL) {
                        return NULL;
                    }
                    jstring name = (*env)->NewStringUTF(env, entry->impl_name);
                    if (name == NULL) {
                        return NULL;
                    }
                    if (entry->ref_kind == 6) {
                        jmethodID find_static = (*env)->GetMethodID(
                                env,
                                lookup_class,
                                "findStatic",
                                "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;");
                        if (find_static == NULL) {
                            return NULL;
                        }
                        return (*env)->CallObjectMethod(env, lookup, find_static, owner_class, name, impl_type);
                    }
                    if (entry->ref_kind == 5 || entry->ref_kind == 9) {
                        jmethodID find_virtual = (*env)->GetMethodID(
                                env,
                                lookup_class,
                                "findVirtual",
                                "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;");
                        if (find_virtual == NULL) {
                            return NULL;
                        }
                        return (*env)->CallObjectMethod(env, lookup, find_virtual, owner_class, name, impl_type);
                    }
                    if (entry->ref_kind == 8) {
                        jmethodID find_constructor = (*env)->GetMethodID(
                                env,
                                lookup_class,
                                "findConstructor",
                                "(Ljava/lang/Class;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;");
                        if (find_constructor == NULL) {
                            return NULL;
                        }
                        return (*env)->CallObjectMethod(env, lookup, find_constructor, owner_class, impl_type);
                    }
                    j2ll_throw_new(env, "java/lang/UnsupportedOperationException", "unsupported lambda implementation handle kind");
                    return NULL;
                }

                static jobject j2ll_lambda_new_from_entry(
                        JNIEnv* env,
                        const j2ll_lambda_entry* entry,
                        jobject capture) {
                    jclass method_handles_class = (*env)->FindClass(env, "java/lang/invoke/MethodHandles");
                    jclass lambda_metafactory_class = (*env)->FindClass(env, "java/lang/invoke/LambdaMetafactory");
                    jclass call_site_class = (*env)->FindClass(env, "java/lang/invoke/CallSite");
                    jclass method_handle_class = (*env)->FindClass(env, "java/lang/invoke/MethodHandle");
                    jclass object_class = (*env)->FindClass(env, "java/lang/Object");
                    jclass class_class = (*env)->FindClass(env, "java/lang/Class");
                    jclass caller_class = (*env)->FindClass(env, entry->caller_owner);
                    jclass owner_class = (*env)->FindClass(env, entry->impl_owner);
                    if (method_handles_class == NULL || lambda_metafactory_class == NULL || call_site_class == NULL
                            || method_handle_class == NULL || object_class == NULL || class_class == NULL
                            || caller_class == NULL || owner_class == NULL) {
                        return NULL;
                    }
                    jmethodID lookup_method = (*env)->GetStaticMethodID(
                            env,
                            method_handles_class,
                            "lookup",
                            "()Ljava/lang/invoke/MethodHandles$Lookup;");
                    jmethodID public_lookup_method = (*env)->GetStaticMethodID(
                            env,
                            method_handles_class,
                            "publicLookup",
                            "()Ljava/lang/invoke/MethodHandles$Lookup;");
                    jmethodID private_lookup_in = (*env)->GetStaticMethodID(
                            env,
                            method_handles_class,
                            "privateLookupIn",
                            "(Ljava/lang/Class;Ljava/lang/invoke/MethodHandles$Lookup;)Ljava/lang/invoke/MethodHandles$Lookup;");
                    jmethodID class_loader_method = (*env)->GetMethodID(
                            env,
                            class_class,
                            "getClassLoader",
                            "()Ljava/lang/ClassLoader;");
                    if (lookup_method == NULL
                            || public_lookup_method == NULL
                            || private_lookup_in == NULL
                            || class_loader_method == NULL) {
                        return NULL;
                    }
                    jobject base_lookup = (*env)->CallStaticObjectMethod(env, method_handles_class, lookup_method);
                    if ((*env)->ExceptionCheck(env) || base_lookup == NULL) {
                        return NULL;
                    }
                    jobject caller_lookup = (*env)->CallStaticObjectMethod(
                            env,
                            method_handles_class,
                            private_lookup_in,
                            caller_class,
                            base_lookup);
                    if ((*env)->ExceptionCheck(env) || caller_lookup == NULL) {
                        return NULL;
                    }
                    jobject impl_lookup;
                    if (strncmp(entry->impl_owner, "java/", 5) == 0 || strncmp(entry->impl_owner, "javax/", 6) == 0) {
                        impl_lookup = (*env)->CallStaticObjectMethod(env, method_handles_class, public_lookup_method);
                    } else {
                        impl_lookup = (*env)->CallStaticObjectMethod(
                                env,
                                method_handles_class,
                                private_lookup_in,
                                owner_class,
                                base_lookup);
                    }
                    if ((*env)->ExceptionCheck(env) || impl_lookup == NULL) {
                        return NULL;
                    }
                    jobject loader = (*env)->CallObjectMethod(env, caller_class, class_loader_method);
                    if ((*env)->ExceptionCheck(env)) {
                        return NULL;
                    }
                    jobject invoked_type = j2ll_method_type_from_descriptor(env, entry->invoked_desc, loader);
                    jobject sam_type = j2ll_method_type_from_descriptor(env, entry->sam_desc, loader);
                    jobject impl_type = j2ll_method_type_from_descriptor(env, entry->impl_desc, loader);
                    jobject instantiated_type = j2ll_method_type_from_descriptor(env, entry->instantiated_desc, loader);
                    if ((*env)->ExceptionCheck(env)
                            || invoked_type == NULL
                            || sam_type == NULL
                            || impl_type == NULL
                            || instantiated_type == NULL) {
                        return NULL;
                    }
                    jobject impl_handle = j2ll_lambda_impl_handle(env, impl_lookup, owner_class, entry, impl_type);
                    if ((*env)->ExceptionCheck(env) || impl_handle == NULL) {
                        return NULL;
                    }
                    jmethodID metafactory = (*env)->GetStaticMethodID(
                            env,
                            lambda_metafactory_class,
                            "metafactory",
                            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;");
                    if (metafactory == NULL) {
                        return NULL;
                    }
                    jstring invoked_name = (*env)->NewStringUTF(env, entry->invoked_name);
                    if (invoked_name == NULL) {
                        return NULL;
                    }
                    jobject call_site = (*env)->CallStaticObjectMethod(
                            env,
                            lambda_metafactory_class,
                            metafactory,
                            caller_lookup,
                            invoked_name,
                            invoked_type,
                            sam_type,
                            impl_handle,
                            instantiated_type);
                    if ((*env)->ExceptionCheck(env) || call_site == NULL) {
                        return NULL;
                    }
                    jmethodID get_target = (*env)->GetMethodID(
                            env,
                            call_site_class,
                            "getTarget",
                            "()Ljava/lang/invoke/MethodHandle;");
                    jmethodID invoke_with_arguments = (*env)->GetMethodID(
                            env,
                            method_handle_class,
                            "invokeWithArguments",
                            "([Ljava/lang/Object;)Ljava/lang/Object;");
                    if (get_target == NULL || invoke_with_arguments == NULL) {
                        return NULL;
                    }
                    jobject factory = (*env)->CallObjectMethod(env, call_site, get_target);
                    if ((*env)->ExceptionCheck(env) || factory == NULL) {
                        return NULL;
                    }
                    jobjectArray arguments = (*env)->NewObjectArray(env, capture == NULL ? 0 : 1, object_class, NULL);
                    if (arguments == NULL) {
                        return NULL;
                    }
                    if (capture != NULL) {
                        (*env)->SetObjectArrayElement(env, arguments, 0, capture);
                        if ((*env)->ExceptionCheck(env)) {
                            return NULL;
                        }
                    }
                    return (*env)->CallObjectMethod(env, factory, invoke_with_arguments, arguments);
                }

                """);
        for (Map.Entry<String, String[]> entry : runtimeTokens.physicalOrder(
                RuntimeTokenDomain.LAMBDA,
                List.copyOf(lambdaSpecs.entrySet()),
                Map.Entry::getKey)) {
            String[] fields = entry.getValue();
            String symbol = helperSymbol(runtimeTokens, entry.getKey());
            builder.append("jobject ")
                    .append(symbol)
                    .append("(JNIEnv* env, jobject capture) {\n")
                    .append("    const char* caller_owner = \"")
                    .append(escapeCString(fields[0]))
                    .append("\";\n    const char* invoked_name = \"")
                    .append(escapeCString(fields[1]))
                    .append("\";\n    const char* invoked_desc = \"")
                    .append(escapeCString(fields[2]))
                    .append("\";\n    const char* sam_desc = \"")
                    .append(escapeCString(fields[3]))
                    .append("\";\n    const char* impl_owner = \"")
                    .append(escapeCString(fields[5]))
                    .append("\";\n    const char* impl_name = \"")
                    .append(escapeCString(fields[6]))
                    .append("\";\n    const char* impl_desc = \"")
                    .append(escapeCString(fields[7]))
                    .append("\";\n    const char* instantiated_desc = \"")
                    .append(escapeCString(fields[8]))
                    .append("\";\n")
                    .append("    j2ll_lambda_entry entry;\n")
                    .append("    entry.caller_owner = caller_owner;\n")
                    .append("    entry.invoked_name = invoked_name;\n")
                    .append("    entry.invoked_desc = invoked_desc;\n")
                    .append("    entry.sam_desc = sam_desc;\n")
                    .append("    entry.ref_kind = ")
                    .append(Integer.parseInt(fields[4]))
                    .append(";\n")
                    .append("    entry.impl_owner = impl_owner;\n")
                    .append("    entry.impl_name = impl_name;\n")
                    .append("    entry.impl_desc = impl_desc;\n")
                    .append("    entry.instantiated_desc = instantiated_desc;\n")
                    .append("    return j2ll_lambda_new_from_entry(env, &entry, capture);\n")
                    .append("}\n\n");
        }
    }

    static String helperSymbol(
            RuntimeTokenMapper runtimeTokens,
            String lambdaIdentity) {
        return runtimeTokens.helperSymbol(
                RuntimeTokenDomain.LAMBDA,
                "lambda_new",
                lambdaIdentity);
    }

    private static String escapeCString(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

}
