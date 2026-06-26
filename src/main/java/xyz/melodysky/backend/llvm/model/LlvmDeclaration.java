package xyz.melodysky.backend.llvm.model;

import java.util.List;
import java.util.Objects;

public record LlvmDeclaration(
        String name,
        String returnType,
        List<String> parameterTypes,
        String comment) {
    public LlvmDeclaration {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(returnType, "returnType");
        parameterTypes = List.copyOf(Objects.requireNonNull(parameterTypes, "parameterTypes"));
    }
}
