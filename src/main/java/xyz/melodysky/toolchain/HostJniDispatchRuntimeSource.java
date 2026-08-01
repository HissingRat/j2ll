package xyz.melodysky.toolchain;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.Map;
import java.util.stream.Collectors;
import xyz.melodysky.packaging.MethodRewriteStrategy;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.runtime.RuntimeTokenDomain;
import xyz.melodysky.runtime.RuntimeTokenMapper;
import xyz.melodysky.runtime.jni.RuntimeLocalAbiDomain;

/**
 * Binding-scoped JVM method dispatch without a global method metadata table.
 */
final class HostJniDispatchRuntimeSource {
    private HostJniDispatchRuntimeSource() {}

    static void append(
            StringBuilder builder,
            List<HostJniCSourceGenerator.Binding> bindings,
            RuntimeTokenMapper runtimeTokens) {
        TreeMap<String, DispatchBinding> entries = new TreeMap<>();
        Map<String, HostJniCSourceGenerator.Binding> internalTargets =
                bindings.stream()
                        .filter(binding -> binding.decision() != null)
                        .filter(binding -> binding.decision().strategy()
                                == MethodRewriteStrategy
                                        .INTERNAL_NATIVE_ONLY)
                        .collect(Collectors.toUnmodifiableMap(
                                binding -> binding.decision()
                                        .method()
                                        .methodKey(),
                                binding -> binding));
        bindings.stream()
                .filter(binding ->
                        binding.path() == NativeImplementationPath.LLVM_NATIVE_PATH)
                .forEach(binding -> {
                    binding.constructorCallKeys().forEach(key -> add(
                            entries,
                            new DispatchBinding(Kind.CONSTRUCTOR, key)));
                    binding.staticCallKeys().forEach(key -> add(
                            entries,
                            new DispatchBinding(Kind.STATIC, key)));
                    binding.templateIrMethod().stream()
                            .flatMap(method -> method.blocks().stream())
                            .flatMap(block -> block.instructions().stream())
                            .filter(instruction ->
                                    instruction.opcode() == IrOpcode.CALL_VIRTUAL
                                            || instruction.opcode()
                                                    == IrOpcode.CALL_INTERFACE)
                            .filter(instruction -> instruction.symbol().isPresent())
                            .filter(instruction -> binding.dispatchKeys()
                                    .contains(instruction.symbol().orElseThrow()))
                            .forEach(instruction -> add(
                                    entries,
                                    new DispatchBinding(
                                            instruction.opcode()
                                                            == IrOpcode.CALL_INTERFACE
                                                    ? Kind.INTERFACE
                                                    : Kind.VIRTUAL,
                                            instruction.symbol().orElseThrow())));
                });
        for (DispatchBinding entry : runtimeTokens.physicalOrder(
                RuntimeTokenDomain.DISPATCH_METHOD,
                List.copyOf(entries.values()),
                DispatchBinding::identity)) {
            appendBinding(
                    builder,
                    runtimeTokens,
                    entry,
                    internalTargets.get(entry.methodKey()));
        }
    }

    static String helperSymbol(
            RuntimeTokenMapper runtimeTokens,
            String operation,
            String methodKey) {
        return runtimeTokens.helperSymbol(
                RuntimeTokenDomain.DISPATCH_METHOD,
                operation,
                methodKey);
    }

    static String returnSuffix(String descriptor) {
        int close = descriptor.indexOf(')');
        char value = descriptor.charAt(close + 1);
        return switch (value) {
            case 'V' -> "void";
            case 'Z' -> "boolean";
            case 'B' -> "byte";
            case 'C' -> "char";
            case 'S' -> "short";
            case 'I' -> "i32";
            case 'J' -> "i64";
            case 'F' -> "f32";
            case 'D' -> "f64";
            case 'L', '[' -> "ref";
            default -> throw new IllegalArgumentException(
                    "unsupported dispatch return descriptor " + descriptor);
        };
    }

    private static void add(
            TreeMap<String, DispatchBinding> entries,
            DispatchBinding entry) {
        entries.putIfAbsent(entry.identity(), entry);
    }

    private static void appendBinding(
            StringBuilder builder,
            RuntimeTokenMapper runtimeTokens,
            DispatchBinding entry,
            HostJniCSourceGenerator.Binding internalTarget) {
        MethodParts parts = MethodParts.parse(entry.methodKey());
        String suffix = returnSuffix(parts.descriptor());
        String operation = switch (entry.kind()) {
            case CONSTRUCTOR -> "constructor_call";
            case STATIC -> "static_call_" + suffix;
            case VIRTUAL -> "virtual_dispatch_" + suffix;
            case INTERFACE -> "interface_dispatch_" + suffix;
        };
        String symbol = helperSymbol(
                runtimeTokens,
                operation,
                entry.methodKey());
        String cReturn = cType(suffix);
        ArrayList<HostJniLocalAbiSource.Parameter> parameters =
                new ArrayList<>();
        parameters.add(new HostJniLocalAbiSource.Parameter(
                "JNIEnv*",
                "env"));
        if (entry.kind() != Kind.STATIC) {
            parameters.add(new HostJniLocalAbiSource.Parameter(
                    "jobject",
                    "receiver"));
        }
        parameters.add(new HostJniLocalAbiSource.Parameter(
                "jvalue*",
                "args"));
        HostJniLocalAbiSource.Emission localAbi =
                HostJniLocalAbiSource.emit(
                        runtimeTokens,
                        RuntimeLocalAbiDomain.DISPATCH,
                        operation,
                        entry.methodKey(),
                        parameters);
        builder.append(cReturn)
                .append(' ')
                .append(symbol)
                .append('(')
                .append(localAbi.parameterDeclarations())
                .append(") {\n");
        if (entry.kind() != Kind.STATIC) {
            builder.append("    if (receiver == NULL) {\n")
                    .append("        j2ll_throw_new(env, \"java/lang/NullPointerException\", \"call receiver is null\");\n")
                    .append(defaultReturn(suffix, "        "))
                    .append("    }\n");
        }
        if (internalTarget != null
                && entry.kind() != Kind.CONSTRUCTOR) {
            new HostJniInternalMethodDispatchSource().appendBody(
                    builder,
                    internalTarget,
                    suffix,
                    entry.kind() == Kind.STATIC);
            builder.append("}\n\n");
            return;
        }
        if (entry.kind() == Kind.VIRTUAL || entry.kind() == Kind.INTERFACE) {
            builder.append(
                    "    jclass cls = (*env)->GetObjectClass(env, receiver);\n");
        } else {
            builder.append("    jclass cls = (*env)->FindClass(env, \"")
                    .append(CSourceEscaper.stringContents(parts.owner()))
                    .append("\");\n");
        }
        builder
                .append("    if (cls == NULL) {\n")
                .append(defaultReturn(suffix, "        "))
                .append("    }\n")
                .append("    jmethodID method = (*env)->")
                .append(entry.kind() == Kind.STATIC
                        ? "GetStaticMethodID"
                        : "GetMethodID")
                .append("(env, cls, \"")
                .append(CSourceEscaper.stringContents(parts.name()))
                .append("\", \"")
                .append(CSourceEscaper.stringContents(parts.descriptor()))
                .append("\");\n")
                .append("    if (method == NULL) {\n")
                .append("        (*env)->DeleteLocalRef(env, cls);\n")
                .append(defaultReturn(suffix, "        "))
                .append("    }\n");
        if (entry.kind() == Kind.CONSTRUCTOR) {
            builder.append("    (*env)->CallNonvirtualVoidMethodA(env, receiver, cls, method, args);\n")
                    .append("    (*env)->DeleteLocalRef(env, cls);\n")
                    .append("}\n\n");
            return;
        }
        String call = (entry.kind() == Kind.STATIC ? "CallStatic" : "Call")
                + jniSuffix(suffix)
                + "MethodA";
        if (suffix.equals("void")) {
            builder.append("    (*env)->")
                    .append(call)
                    .append("(env, ")
                    .append(entry.kind() == Kind.STATIC ? "cls" : "receiver")
                    .append(", method, args);\n")
                    .append("    (*env)->DeleteLocalRef(env, cls);\n")
                    .append("}\n\n");
            return;
        }
        builder.append("    ")
                .append(jniType(suffix))
                .append(" result = (*env)->")
                .append(call)
                .append("(env, ")
                .append(entry.kind() == Kind.STATIC ? "cls" : "receiver")
                .append(", method, args);\n")
                .append("    (*env)->DeleteLocalRef(env, cls);\n")
                .append("    return ")
                .append(readExpression(suffix))
                .append(";\n")
                .append("}\n\n");
    }

    private static String cType(String suffix) {
        return switch (suffix) {
            case "void" -> "void";
            case "i64" -> "int64_t";
            case "f32" -> "float";
            case "f64" -> "double";
            case "ref" -> "jobject";
            case "boolean", "byte", "char", "short", "i32" -> "int32_t";
            default -> throw new IllegalArgumentException(
                    "unsupported dispatch suffix " + suffix);
        };
    }

    private static String jniType(String suffix) {
        return switch (suffix) {
            case "i64" -> "jlong";
            case "f32" -> "jfloat";
            case "f64" -> "jdouble";
            case "ref" -> "jobject";
            case "boolean" -> "jboolean";
            case "byte" -> "jbyte";
            case "char" -> "jchar";
            case "short" -> "jshort";
            case "i32" -> "jint";
            default -> throw new IllegalArgumentException(
                    "unsupported dispatch suffix " + suffix);
        };
    }

    private static String jniSuffix(String suffix) {
        return switch (suffix) {
            case "void" -> "Void";
            case "i64" -> "Long";
            case "f32" -> "Float";
            case "f64" -> "Double";
            case "ref" -> "Object";
            case "boolean" -> "Boolean";
            case "byte" -> "Byte";
            case "char" -> "Char";
            case "short" -> "Short";
            case "i32" -> "Int";
            default -> throw new IllegalArgumentException(
                    "unsupported dispatch suffix " + suffix);
        };
    }

    private static String readExpression(String suffix) {
        return switch (suffix) {
            case "i64" -> "(int64_t)result";
            case "f32" -> "(float)result";
            case "f64" -> "(double)result";
            case "ref" -> "result";
            case "boolean" -> "result == JNI_FALSE ? 0 : 1";
            case "byte", "char", "short", "i32" -> "(int32_t)result";
            default -> throw new IllegalArgumentException(
                    "unsupported dispatch suffix " + suffix);
        };
    }

    private static String defaultReturn(
            String suffix,
            String indent) {
        if (suffix.equals("void")) {
            return indent + "return;\n";
        }
        return indent
                + "return "
                + (suffix.equals("ref") ? "NULL" : "0")
                + ";\n";
    }

    private enum Kind {
        CONSTRUCTOR,
        STATIC,
        VIRTUAL,
        INTERFACE
    }

    private record DispatchBinding(Kind kind, String methodKey) {
        String identity() {
            return kind + "\0" + methodKey;
        }
    }

    private record MethodParts(
            String owner,
            String name,
            String descriptor) {
        static MethodParts parse(String methodKey) {
            int ownerEnd = methodKey.indexOf('#');
            int descriptorStart = methodKey.indexOf('!');
            if (ownerEnd < 1 || descriptorStart <= ownerEnd + 1) {
                throw new IllegalArgumentException(
                        "invalid method key: " + methodKey);
            }
            return new MethodParts(
                    methodKey.substring(0, ownerEnd),
                    methodKey.substring(ownerEnd + 1, descriptorStart),
                    methodKey.substring(descriptorStart + 1));
        }
    }
}
