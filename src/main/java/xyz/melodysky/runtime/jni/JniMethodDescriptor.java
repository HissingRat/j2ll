package xyz.melodysky.runtime.jni;

import java.util.List;
import java.util.Objects;

public record JniMethodDescriptor(
        String owner,
        String methodName,
        String javaDescriptor,
        boolean staticMethod,
        List<String> javaParameterDescriptors,
        String javaReturnDescriptor,
        List<String> jniParameterTypes,
        String jniReturnType) {
    public JniMethodDescriptor {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(methodName, "methodName");
        Objects.requireNonNull(javaDescriptor, "javaDescriptor");
        javaParameterDescriptors = List.copyOf(Objects.requireNonNull(javaParameterDescriptors, "javaParameterDescriptors"));
        Objects.requireNonNull(javaReturnDescriptor, "javaReturnDescriptor");
        jniParameterTypes = List.copyOf(Objects.requireNonNull(jniParameterTypes, "jniParameterTypes"));
        Objects.requireNonNull(jniReturnType, "jniReturnType");
    }

    public List<String> implicitParameterTypes() {
        return List.of("JNIEnv*", staticMethod ? "jclass" : "jobject");
    }

    public List<String> nativeParameterTypes() {
        java.util.ArrayList<String> parameters = new java.util.ArrayList<>(implicitParameterTypes());
        parameters.addAll(jniParameterTypes);
        return List.copyOf(parameters);
    }

    public String cPrototype(String nativeSymbol) {
        return jniReturnType + " " + nativeSymbol + "(" + String.join(", ", nativeParameterTypes()) + ")";
    }
}
