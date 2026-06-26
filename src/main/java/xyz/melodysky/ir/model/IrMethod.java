package xyz.melodysky.ir.model;

import java.util.List;
import java.util.Objects;

public record IrMethod(
        String owner,
        String name,
        String descriptor,
        IrType returnType,
        List<IrValue> parameters,
        List<IrBlock> blocks) {
    public IrMethod {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(returnType, "returnType");
        parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters"));
        blocks = List.copyOf(Objects.requireNonNull(blocks, "blocks"));
    }

    public String methodKey() {
        return owner + "#" + name + "!" + descriptor;
    }
}
