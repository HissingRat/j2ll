package xyz.melodysky.backend.llvm.model;

import java.util.Objects;
import java.util.Optional;

/** Stable, non-textual evidence that prevented an unwind-omitting emission. */
public record LlvmNativeUnwindFinding(
        String functionName,
        Optional<String> blockName,
        Optional<Integer> instructionIndex,
        LlvmNativeUnwindSemantics semantics) {
    public LlvmNativeUnwindFinding {
        Objects.requireNonNull(functionName, "functionName");
        Objects.requireNonNull(blockName, "blockName");
        Objects.requireNonNull(instructionIndex, "instructionIndex");
        Objects.requireNonNull(semantics, "semantics");
        if (semantics == LlvmNativeUnwindSemantics.PROVEN_ABSENT) {
            throw new IllegalArgumentException(
                    "proven-absent native unwind is not a finding");
        }
        if (instructionIndex.filter(index -> index < 0).isPresent()) {
            throw new IllegalArgumentException("instruction index must be non-negative");
        }
    }

    public static LlvmNativeUnwindFinding function(
            String functionName,
            LlvmNativeUnwindSemantics semantics) {
        return new LlvmNativeUnwindFinding(
                functionName,
                Optional.empty(),
                Optional.empty(),
                semantics);
    }

    public static LlvmNativeUnwindFinding instruction(
            String functionName,
            String blockName,
            int instructionIndex,
            LlvmNativeUnwindSemantics semantics) {
        return new LlvmNativeUnwindFinding(
                functionName,
                Optional.of(blockName),
                Optional.of(instructionIndex),
                semantics);
    }
}
