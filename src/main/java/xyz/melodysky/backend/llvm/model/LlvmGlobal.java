package xyz.melodysky.backend.llvm.model;

import java.util.Objects;

public record LlvmGlobal(String name, String definition) {
    public LlvmGlobal {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("LLVM global name must not be blank");
        }
        Objects.requireNonNull(definition, "definition");
        if (definition.isBlank()) {
            throw new IllegalArgumentException("LLVM global definition must not be blank");
        }
    }
}
