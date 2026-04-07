package xyz.melodysky.ir.model;

import java.util.List;
import java.util.Objects;

public record IrMethodRef(
        IrClassRef owner,
        String name,
        IrType returnType,
        List<IrType> parameterTypes,
        CallKind callKind
) {

    public IrMethodRef {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(returnType, "returnType");
        Objects.requireNonNull(parameterTypes, "parameterTypes");
        Objects.requireNonNull(callKind, "callKind");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        parameterTypes = List.copyOf(parameterTypes);
    }

    public enum CallKind {
        STATIC,
        VIRTUAL,
        SPECIAL,
        INTERFACE,
        HELPER
    }
}
