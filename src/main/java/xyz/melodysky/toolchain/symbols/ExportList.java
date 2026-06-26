package xyz.melodysky.toolchain.symbols;

import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

public record ExportList(List<ExportedSymbol> symbols) {
    public ExportList {
        TreeSet<ExportedSymbol> sorted = new TreeSet<>(Objects.requireNonNull(symbols, "symbols"));
        symbols = List.copyOf(sorted);
    }

    public boolean contains(String symbolName) {
        return symbols.stream().anyMatch(symbol -> symbol.name().equals(symbolName));
    }
}
