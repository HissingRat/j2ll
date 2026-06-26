package xyz.melodysky.ir.model;

import java.util.Objects;

public record IrValue(String name, IrType type) implements Comparable<IrValue> {
    public IrValue {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        if (name.isBlank()) {
            throw new IllegalArgumentException("IR value name must not be blank");
        }
    }

    @Override
    public int compareTo(IrValue other) {
        return name.compareTo(other.name);
    }
}
