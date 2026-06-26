package xyz.melodysky.frontend.cfg;

import java.util.List;
import java.util.Objects;

public record BytecodeBasicBlock(
        int id,
        int startInstructionIndex,
        int endInstructionIndexExclusive,
        boolean reachable,
        List<String> handlerCatchTypes) {
    public BytecodeBasicBlock {
        if (id < 0) {
            throw new IllegalArgumentException("block id must be non-negative");
        }
        if (startInstructionIndex < 0 || endInstructionIndexExclusive < startInstructionIndex) {
            throw new IllegalArgumentException("invalid block instruction range");
        }
        handlerCatchTypes = List.copyOf(Objects.requireNonNull(handlerCatchTypes, "handlerCatchTypes"));
    }

    public boolean isExceptionHandler() {
        return !handlerCatchTypes.isEmpty();
    }

    public BytecodeBasicBlock withReachable(boolean newReachable) {
        return new BytecodeBasicBlock(
                id,
                startInstructionIndex,
                endInstructionIndexExclusive,
                newReachable,
                handlerCatchTypes);
    }
}
