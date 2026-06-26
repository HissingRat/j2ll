package xyz.melodysky.runtime.jni;

import java.util.ArrayList;
import java.util.List;

public final class JniTypeMapper {
    public JniMethodDescriptor methodDescriptor(
            String owner,
            String methodName,
            String descriptor,
            boolean staticMethod) {
        List<String> javaParameters = parameterDescriptors(descriptor);
        return new JniMethodDescriptor(
                owner,
                methodName,
                descriptor,
                staticMethod,
                javaParameters,
                returnDescriptor(descriptor),
                javaParameters.stream().map(this::jniType).toList(),
                jniType(returnDescriptor(descriptor)));
    }

    public String jniType(String descriptor) {
        return switch (descriptor.charAt(0)) {
            case 'V' -> "void";
            case 'Z' -> "jboolean";
            case 'B' -> "jbyte";
            case 'C' -> "jchar";
            case 'S' -> "jshort";
            case 'I' -> "jint";
            case 'J' -> "jlong";
            case 'F' -> "jfloat";
            case 'D' -> "jdouble";
            case '[' -> arrayJniType(descriptor);
            case 'L' -> objectJniType(descriptor);
            default -> throw new IllegalArgumentException("unsupported JVM descriptor: " + descriptor);
        };
    }

    public List<String> parameterDescriptors(String methodDescriptor) {
        int index = 1;
        ArrayList<String> descriptors = new ArrayList<>();
        while (methodDescriptor.charAt(index) != ')') {
            int start = index;
            while (methodDescriptor.charAt(index) == '[') {
                index++;
            }
            char kind = methodDescriptor.charAt(index);
            if (kind == 'L') {
                index = methodDescriptor.indexOf(';', index) + 1;
            } else {
                index++;
            }
            descriptors.add(methodDescriptor.substring(start, index));
        }
        return List.copyOf(descriptors);
    }

    public String returnDescriptor(String methodDescriptor) {
        return methodDescriptor.substring(methodDescriptor.indexOf(')') + 1);
    }

    private String objectJniType(String descriptor) {
        return switch (descriptor) {
            case "Ljava/lang/String;" -> "jstring";
            case "Ljava/lang/Class;" -> "jclass";
            default -> "jobject";
        };
    }

    private String arrayJniType(String descriptor) {
        if (descriptor.length() == 2) {
            return switch (descriptor.charAt(1)) {
                case 'Z' -> "jbooleanArray";
                case 'B' -> "jbyteArray";
                case 'C' -> "jcharArray";
                case 'S' -> "jshortArray";
                case 'I' -> "jintArray";
                case 'J' -> "jlongArray";
                case 'F' -> "jfloatArray";
                case 'D' -> "jdoubleArray";
                default -> "jarray";
            };
        }
        if (descriptor.startsWith("[L") && descriptor.endsWith(";")) {
            return "jobjectArray";
        }
        return "jobjectArray";
    }
}
