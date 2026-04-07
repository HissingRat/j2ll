package xyz.melodysky.ir.util;

import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrType;

public final class IrDescriptors {

    private IrDescriptors() {
    }

    public static String methodDescriptor(IrMethod method) {
        StringBuilder builder = new StringBuilder();
        builder.append('(');
        for (IrType parameterType : method.parameterTypes()) {
            builder.append(typeDescriptor(parameterType));
        }
        builder.append(')').append(typeDescriptor(method.returnType()));
        return builder.toString();
    }

    public static String typeDescriptor(IrType type) {
        return switch (type.kind()) {
            case VOID -> "V";
            case BOOLEAN -> "Z";
            case BYTE -> "B";
            case SHORT -> "S";
            case CHAR -> "C";
            case INT -> "I";
            case LONG -> "J";
            case FLOAT -> "F";
            case DOUBLE -> "D";
            case REFERENCE -> "L" + type.displayName() + ";";
            case ARRAY -> "[" + typeDescriptor(arrayElementType(type));
        };
    }

    private static IrType arrayElementType(IrType type) {
        String displayName = type.displayName();
        if (!displayName.endsWith("[]")) {
            throw new IllegalArgumentException("Array type does not end with []: " + displayName);
        }
        String elementDisplayName = displayName.substring(0, displayName.length() - 2);
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
