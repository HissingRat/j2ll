package xyz.melodysky.ir.model;

import java.util.List;
import java.util.Objects;

public record IrClass(IrClassRef reference, List<IrMethod> methods) {

    public IrClass {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(methods, "methods");
        methods = List.copyOf(methods);
    }
}
