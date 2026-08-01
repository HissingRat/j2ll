package xyz.melodysky.toolchain;

import xyz.melodysky.runtime.jni.JniMethodDescriptor;

/**
 * Emits the local-frame bridge from a lowered caller to an internal-only
 * native method entry.
 */
final class HostJniInternalMethodDispatchSource {
    void appendBody(
            StringBuilder source,
            HostJniCSourceGenerator.Binding target,
            String returnSuffix,
            boolean staticCall) {
        JniMethodDescriptor descriptor = target.descriptor();
        if (descriptor.staticMethod() != staticCall) {
            throw new IllegalArgumentException(
                    "internal dispatch kind does not match target descriptor");
        }
        if (staticCall) {
            new HostJniDefiningOwnerSource().appendLookup(
                    source,
                    target.decision().method().owner(),
                    "target_owner");
            source.append(defaultReturn(returnSuffix, "        "))
                    .append("    }\n");
        }
        int capacity = Math.max(
                16,
                8 + descriptor.javaParameterDescriptors().size());
        source.append("    if ((*env)->PushLocalFrame(env, ")
                .append(capacity)
                .append(") < 0) {\n");
        if (staticCall) {
            source.append(
                    "        (*env)->DeleteLocalRef(env, target_owner);\n");
        }
        source.append(defaultReturn(returnSuffix, "        "))
                .append("    }\n");

        String invocation = invocation(target, staticCall);
        if (returnSuffix.equals("void")) {
            source.append("    ").append(invocation).append(";\n");
        } else {
            source.append("    ")
                    .append(descriptor.jniReturnType())
                    .append(" result = ")
                    .append(invocation)
                    .append(";\n");
        }
        appendPendingExceptionTransfer(
                source,
                returnSuffix,
                staticCall);
        if (returnSuffix.equals("ref")) {
            source.append(
                    "    jobject promoted_result = (*env)->PopLocalFrame(env, result);\n");
        } else {
            source.append(
                    "    (void)(*env)->PopLocalFrame(env, NULL);\n");
        }
        if (staticCall) {
            source.append(
                    "    (*env)->DeleteLocalRef(env, target_owner);\n");
        }
        if (returnSuffix.equals("void")) {
            source.append("    return;\n");
        } else if (returnSuffix.equals("ref")) {
            source.append("    return promoted_result;\n");
        } else if (returnSuffix.equals("boolean")) {
            source.append(
                    "    return result == JNI_FALSE ? 0 : 1;\n");
        } else {
            source.append("    return (")
                    .append(cType(returnSuffix))
                    .append(")result;\n");
        }
    }

    private String invocation(
            HostJniCSourceGenerator.Binding target,
            boolean staticCall) {
        StringBuilder invocation = new StringBuilder()
                .append(target.entry().nativeSymbol())
                .append("(env, ")
                .append(staticCall ? "target_owner" : "receiver");
        for (int index = 0;
                index < target.descriptor()
                        .javaParameterDescriptors()
                        .size();
                index++) {
            invocation.append(", ")
                    .append(argumentExpression(
                            target.descriptor()
                                    .javaParameterDescriptors()
                                    .get(index),
                            target.descriptor()
                                    .jniParameterTypes()
                                    .get(index),
                            index));
        }
        return invocation.append(')').toString();
    }

    private String argumentExpression(
            String descriptor,
            String jniType,
            int index) {
        String element = "args[" + index + "]";
        return switch (descriptor.charAt(0)) {
            case 'Z' -> "(jboolean)(" + element
                    + ".z == JNI_FALSE ? JNI_FALSE : JNI_TRUE)";
            case 'B' -> "(jbyte)" + element + ".b";
            case 'C' -> "(jchar)" + element + ".c";
            case 'S' -> "(jshort)" + element + ".s";
            case 'I' -> "(jint)" + element + ".i";
            case 'J' -> "(jlong)" + element + ".j";
            case 'F' -> "(jfloat)" + element + ".f";
            case 'D' -> "(jdouble)" + element + ".d";
            case 'L', '[' -> "("
                    + jniType
                    + ")"
                    + element
                    + ".l";
            default -> throw new IllegalArgumentException(
                    "unsupported internal dispatch argument descriptor: "
                            + descriptor);
        };
    }

    private void appendPendingExceptionTransfer(
            StringBuilder source,
            String returnSuffix,
            boolean staticCall) {
        source.append("    jthrowable pending = (*env)->ExceptionOccurred(env);\n")
                .append("    if (pending != NULL) {\n")
                .append("        (*env)->ExceptionClear(env);\n")
                .append("        jthrowable promoted = (jthrowable)(*env)->PopLocalFrame(env, pending);\n");
        if (staticCall) {
            source.append(
                    "        (*env)->DeleteLocalRef(env, target_owner);\n");
        }
        source.append("        if (promoted == NULL\n")
                .append("                || (*env)->Throw(env, promoted) != JNI_OK\n")
                .append("                || !(*env)->ExceptionCheck(env)) {\n")
                .append("            (*env)->FatalError(env, \"internal native exception restore failed\");\n")
                .append("        }\n")
                .append("        (*env)->DeleteLocalRef(env, promoted);\n")
                .append(defaultReturn(returnSuffix, "        "))
                .append("    }\n");
    }

    private String cType(String suffix) {
        return switch (suffix) {
            case "i64" -> "int64_t";
            case "f32" -> "float";
            case "f64" -> "double";
            case "boolean", "byte", "char", "short", "i32" ->
                    "int32_t";
            default -> throw new IllegalArgumentException(
                    "unsupported internal dispatch suffix " + suffix);
        };
    }

    private String defaultReturn(
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
}
