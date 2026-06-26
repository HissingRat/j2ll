package xyz.melodysky.backend.llvm.model;

import java.util.List;
import java.util.Objects;

public record LlvmBasicBlock(String name, List<LlvmInstruction> instructions, LlvmTerminator terminator) {
    public LlvmBasicBlock {
        Objects.requireNonNull(name, "name");
        instructions = List.copyOf(Objects.requireNonNull(instructions, "instructions"));
        Objects.requireNonNull(terminator, "terminator");
    }
}
