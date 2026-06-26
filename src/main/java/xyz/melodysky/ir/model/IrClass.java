package xyz.melodysky.ir.model;

import java.util.List;
import java.util.Objects;

public record IrClass(String internalName, List<IrMethod> methods) {
    public IrClass {
        Objects.requireNonNull(internalName, "internalName");
        methods = List.copyOf(Objects.requireNonNull(methods, "methods"));
    }
}
