package xyz.melodysky.ir.model;

import java.util.List;
import java.util.Objects;

public record IrProgram(List<IrClass> classes) {

    public IrProgram {
        Objects.requireNonNull(classes, "classes");
        classes = List.copyOf(classes);
    }
}
