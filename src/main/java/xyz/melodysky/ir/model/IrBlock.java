package xyz.melodysky.ir.model;

import java.util.List;
import java.util.Objects;

public record IrBlock(
        String name,
        List<IrValue> parameters,
        List<String> exceptionCatchTypes,
        List<IrExceptionEdge> exceptionEdges,
        List<IrInstruction> instructions,
        IrTerminator terminator) {
    public IrBlock {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("IR block name must not be blank");
        }
        parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters"));
        exceptionCatchTypes = List.copyOf(Objects.requireNonNull(exceptionCatchTypes, "exceptionCatchTypes"));
        exceptionEdges = List.copyOf(Objects.requireNonNull(exceptionEdges, "exceptionEdges"));
        instructions = List.copyOf(Objects.requireNonNull(instructions, "instructions"));
        Objects.requireNonNull(terminator, "terminator");
    }

    public IrBlock(
            String name,
            List<IrValue> parameters,
            List<String> exceptionCatchTypes,
            List<IrInstruction> instructions,
            IrTerminator terminator) {
        this(name, parameters, exceptionCatchTypes, List.of(), instructions, terminator);
    }

    public IrBlock(String name, List<IrValue> parameters, List<IrInstruction> instructions, IrTerminator terminator) {
        this(name, parameters, List.of(), List.of(), instructions, terminator);
    }

    public IrBlock(String name, List<IrInstruction> instructions, IrTerminator terminator) {
        this(name, List.of(), List.of(), List.of(), instructions, terminator);
    }

    public boolean isExceptionHandler() {
        return !exceptionCatchTypes.isEmpty();
    }
}
