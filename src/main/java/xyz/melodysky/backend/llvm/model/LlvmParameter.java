package xyz.melodysky.backend.llvm.model;

import java.util.Objects;

public record LlvmParameter(LlvmType type, String name) {
    public LlvmParameter {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(name, "name");
    }
}
