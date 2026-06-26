package xyz.melodysky.ir.model;

import java.util.Objects;

public record IrExceptionEdge(String target, String catchType) {
    public IrExceptionEdge {
        Objects.requireNonNull(target, "target");
        if (target.isBlank()) {
            throw new IllegalArgumentException("exception edge target must not be blank");
        }
        Objects.requireNonNull(catchType, "catchType");
        if (catchType.isBlank()) {
            throw new IllegalArgumentException("exception edge catch type must not be blank");
        }
    }
}
