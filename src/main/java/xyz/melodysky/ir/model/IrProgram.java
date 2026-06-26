package xyz.melodysky.ir.model;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record IrProgram(List<IrClass> classes) {
    public IrProgram {
        classes = classes.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(IrClass::internalName))
                .toList();
    }
}
