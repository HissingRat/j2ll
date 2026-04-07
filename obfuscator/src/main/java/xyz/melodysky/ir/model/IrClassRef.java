package xyz.melodysky.ir.model;

import java.util.Objects;

public record IrClassRef(String internalName) {

    public IrClassRef {
        Objects.requireNonNull(internalName, "internalName");
        if (internalName.isBlank()) {
            throw new IllegalArgumentException("internalName must not be blank");
        }
        internalName = internalName.replace('.', '/');
    }

    public String simpleName() {
        int separator = internalName.lastIndexOf('/');
        return separator >= 0 ? internalName.substring(separator + 1) : internalName;
    }
}
