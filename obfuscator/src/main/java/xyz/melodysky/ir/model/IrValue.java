package xyz.melodysky.ir.model;

import java.util.Locale;
import java.util.Objects;

public record IrValue(int id, IrType type, String debugName) {

    public IrValue {
        if (id < 0) {
            throw new IllegalArgumentException("id must be non-negative");
        }
        Objects.requireNonNull(type, "type");
    }

    public IrValue(int id, IrType type) {
        this(id, type, null);
    }

    public String symbol() {
        if (debugName == null || debugName.isBlank()) {
            return "%v" + id;
        }
        return "%" + sanitize(debugName) + id;
    }

    public String typedSymbol() {
        return symbol() + ":" + type.displayName();
    }

    private static String sanitize(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (char current : value.toLowerCase(Locale.ROOT).toCharArray()) {
            if ((current >= 'a' && current <= 'z') || (current >= '0' && current <= '9')) {
                builder.append(current);
            } else {
                builder.append('_');
            }
        }
        return builder.toString();
    }
}
