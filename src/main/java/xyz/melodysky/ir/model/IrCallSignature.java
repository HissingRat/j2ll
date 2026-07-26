package xyz.melodysky.ir.model;

import java.util.List;
import java.util.Objects;

/**
 * Typed SSA call signature. Parameter types are the actual IR operands, so an
 * instance-call receiver is included as the first parameter.
 */
public record IrCallSignature(IrType returnType, List<IrType> parameterTypes)
        implements Comparable<IrCallSignature> {
    public IrCallSignature {
        Objects.requireNonNull(returnType, "returnType");
        parameterTypes = List.copyOf(Objects.requireNonNull(parameterTypes, "parameterTypes"));
        if (parameterTypes.stream().anyMatch(type -> type == IrType.VOID)) {
            throw new IllegalArgumentException("call parameter type must not be void");
        }
    }

    public static IrCallSignature fromInstruction(IrInstruction instruction) {
        Objects.requireNonNull(instruction, "instruction");
        return new IrCallSignature(
                instruction.result().map(IrValue::type).orElse(IrType.VOID),
                instruction.operands().stream().map(IrValue::type).toList());
    }

    public static IrCallSignature fromMethod(IrMethod method) {
        Objects.requireNonNull(method, "method");
        return new IrCallSignature(
                method.returnType(),
                method.parameters().stream().map(IrValue::type).toList());
    }

    @Override
    public int compareTo(IrCallSignature other) {
        int byReturn = returnType.name().compareTo(other.returnType.name());
        if (byReturn != 0) {
            return byReturn;
        }
        return parameterKey().compareTo(other.parameterKey());
    }

    public String stableKey() {
        return returnType.name() + "(" + parameterKey() + ")";
    }

    private String parameterKey() {
        return parameterTypes.stream()
                .map(Enum::name)
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }
}
