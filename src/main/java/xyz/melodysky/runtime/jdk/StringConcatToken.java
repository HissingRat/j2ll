package xyz.melodysky.runtime.jdk;

import java.util.Objects;

public record StringConcatToken(StringConcatTokenKind kind, int operandIndex, String constant) {
    public StringConcatToken {
        Objects.requireNonNull(kind, "kind");
        if (kind == StringConcatTokenKind.OPERAND && operandIndex < 0) {
            throw new IllegalArgumentException("operand token index must be non-negative");
        }
        if (kind == StringConcatTokenKind.CONSTANT) {
            Objects.requireNonNull(constant, "constant");
        }
    }

    public static StringConcatToken operand(int index) {
        return new StringConcatToken(StringConcatTokenKind.OPERAND, index, null);
    }

    public static StringConcatToken constant(String value) {
        return new StringConcatToken(StringConcatTokenKind.CONSTANT, -1, value);
    }
}
