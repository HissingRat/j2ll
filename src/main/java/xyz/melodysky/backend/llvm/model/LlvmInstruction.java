package xyz.melodysky.backend.llvm.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record LlvmInstruction(
        Optional<String> result,
        LlvmType type,
        String opcode,
        List<String> operands,
        Optional<String> rawText) {
    public LlvmInstruction {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(opcode, "opcode");
        operands = List.copyOf(Objects.requireNonNull(operands, "operands"));
        Objects.requireNonNull(rawText, "rawText");
    }

    public LlvmInstruction(Optional<String> result, LlvmType type, String opcode, List<String> operands) {
        this(result, type, opcode, operands, Optional.empty());
    }

    public static LlvmInstruction raw(Optional<String> result, String text) {
        return new LlvmInstruction(result, LlvmType.VOID, "raw", List.of(), Optional.of(text));
    }
}
