package xyz.melodysky.backend.llvm.model;

import java.util.Objects;

/** One typed SSA operand of a structured direct LLVM call. */
public record LlvmCallArgument(LlvmType type, String value) {
    public LlvmCallArgument {
        Objects.requireNonNull(type, "type");
        if (Objects.requireNonNull(value, "value").isBlank()) {
            throw new IllegalArgumentException(
                    "LLVM call argument value must not be blank");
        }
    }
}
