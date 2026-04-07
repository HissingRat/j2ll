package xyz.melodysky.ir.model;

import java.util.Objects;

public record IrFieldRef(IrClassRef owner, String name, IrType type, boolean isStatic) {

    public IrFieldRef {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }
}
