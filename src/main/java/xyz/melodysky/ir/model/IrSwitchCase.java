package xyz.melodysky.ir.model;

import java.util.List;
import java.util.Objects;

public record IrSwitchCase(int key, String target, List<IrValue> arguments) implements Comparable<IrSwitchCase> {
    public IrSwitchCase {
        Objects.requireNonNull(target, "target");
        arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
    }

    public IrSwitchCase(int key, String target) {
        this(key, target, List.of());
    }

    @Override
    public int compareTo(IrSwitchCase other) {
        int byKey = Integer.compare(key, other.key);
        return byKey != 0 ? byKey : target.compareTo(other.target);
    }
}
