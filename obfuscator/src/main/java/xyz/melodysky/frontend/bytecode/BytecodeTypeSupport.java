package xyz.melodysky.frontend.bytecode;

import xyz.melodysky.ir.model.IrType;

final class BytecodeTypeSupport {
    private BytecodeTypeSupport() {
    }

    static boolean isIntLike(IrType type) {
        return type == IrType.BOOLEAN
                || type == IrType.BYTE
                || type == IrType.SHORT
                || type == IrType.CHAR
                || type == IrType.INT;
    }

    static boolean isReferenceLike(IrType type) {
        return !type.isPrimitive() && type != IrType.VOID;
    }

    static boolean isNumericPrimitive(IrType type) {
        return type.isPrimitive() && type != IrType.BOOLEAN;
    }

    static boolean matchesArrayOpcodeElementType(IrType opcodeElementType, IrType actualElementType) {
        if (opcodeElementType.equals(actualElementType)) {
            return true;
        }
        return opcodeElementType == IrType.BYTE && actualElementType == IrType.BOOLEAN;
    }

    static IrType arrayElementType(IrType arrayType) {
        if (arrayType.kind() != IrType.Kind.ARRAY || !arrayType.displayName().endsWith("[]")) {
            throw new IllegalArgumentException("Not an array type: " + arrayType.displayName());
        }
        String elementDisplayName = arrayType.displayName().substring(0, arrayType.displayName().length() - 2);
        return switch (elementDisplayName) {
            case "boolean" -> IrType.BOOLEAN;
            case "byte" -> IrType.BYTE;
            case "short" -> IrType.SHORT;
            case "char" -> IrType.CHAR;
            case "int" -> IrType.INT;
            case "long" -> IrType.LONG;
            case "float" -> IrType.FLOAT;
            case "double" -> IrType.DOUBLE;
            default -> {
                if (elementDisplayName.endsWith("[]")) {
                    yield IrType.array(arrayElementType(new IrType(IrType.Kind.ARRAY, elementDisplayName)));
                }
                yield IrType.reference(elementDisplayName);
            }
        };
    }
}
