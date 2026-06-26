package xyz.melodysky.runtime;

import java.util.List;
import java.util.Objects;

public record RuntimeHelperSignature(String returnType, List<String> parameterTypes) {
    private static final RuntimeAbi ABI = new RuntimeAbi();

    public RuntimeHelperSignature {
        Objects.requireNonNull(returnType, "returnType");
        parameterTypes = List.copyOf(Objects.requireNonNull(parameterTypes, "parameterTypes"));
    }

    public String llvmParameterList() {
        return parameterTypes.stream().map(ABI::llvmType).collect(java.util.stream.Collectors.joining(", "));
    }
}
