package xyz.melodysky.ir.model;

import java.util.Objects;

public record IrType(Kind kind, String displayName) {

    public static final IrType VOID = new IrType(Kind.VOID, "void");
    public static final IrType BOOLEAN = new IrType(Kind.BOOLEAN, "boolean");
    public static final IrType BYTE = new IrType(Kind.BYTE, "byte");
    public static final IrType SHORT = new IrType(Kind.SHORT, "short");
    public static final IrType CHAR = new IrType(Kind.CHAR, "char");
    public static final IrType INT = new IrType(Kind.INT, "int");
    public static final IrType LONG = new IrType(Kind.LONG, "long");
    public static final IrType FLOAT = new IrType(Kind.FLOAT, "float");
    public static final IrType DOUBLE = new IrType(Kind.DOUBLE, "double");

    public IrType {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(displayName, "displayName");
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
    }

    public static IrType reference(String internalName) {
        String normalized = normalizeInternalName(internalName);
        return new IrType(Kind.REFERENCE, normalized);
    }

    public static IrType array(IrType elementType) {
        Objects.requireNonNull(elementType, "elementType");
        if (elementType == VOID) {
            throw new IllegalArgumentException("void[] is not a valid IR type");
        }
        return new IrType(Kind.ARRAY, elementType.displayName() + "[]");
    }

    public boolean isPrimitive() {
        return switch (kind) {
            case BOOLEAN, BYTE, SHORT, CHAR, INT, LONG, FLOAT, DOUBLE -> true;
            case VOID, REFERENCE, ARRAY -> false;
        };
    }

    public boolean isWide() {
        return kind == Kind.LONG || kind == Kind.DOUBLE;
    }

    private static String normalizeInternalName(String internalName) {
        Objects.requireNonNull(internalName, "internalName");
        String normalized = internalName.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("internalName must not be blank");
        }
        return normalized.replace('.', '/');
    }

    public enum Kind {
        VOID,
        BOOLEAN,
        BYTE,
        SHORT,
        CHAR,
        INT,
        LONG,
        FLOAT,
        DOUBLE,
        REFERENCE,
        ARRAY
    }
}
