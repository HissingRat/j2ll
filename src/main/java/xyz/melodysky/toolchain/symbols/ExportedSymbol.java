package xyz.melodysky.toolchain.symbols;

import java.util.Objects;

public record ExportedSymbol(String name) implements Comparable<ExportedSymbol> {
    public ExportedSymbol {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("symbol name must not be blank");
        }
    }

    @Override
    public int compareTo(ExportedSymbol other) {
        return name.compareTo(other.name);
    }
}
