package xyz.melodysky.ir.model;

import java.util.List;
import java.util.Objects;

public record IrBlock(String label, List<IrInstruction> instructions, IrTerminator terminator) {

    public IrBlock {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(instructions, "instructions");
        Objects.requireNonNull(terminator, "terminator");
        if (label.isBlank()) {
            throw new IllegalArgumentException("label must not be blank");
        }
        instructions = List.copyOf(instructions);
    }
}
