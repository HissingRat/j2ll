package xyz.melodysky.backend.llvm.model;

import java.util.List;
import java.util.Objects;

/** Structured direct-call evidence consumed by emission and model gates. */
public record LlvmDirectCallRef(
        String target,
        LlvmType returnType,
        List<LlvmCallArgument> arguments) {
    public LlvmDirectCallRef {
        if (!Objects.requireNonNull(target, "target")
                .matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException(
                    "LLVM direct-call target must be an identifier");
        }
        Objects.requireNonNull(returnType, "returnType");
        arguments = List.copyOf(
                Objects.requireNonNull(arguments, "arguments"));
    }
}
