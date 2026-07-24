package xyz.melodysky.ir.ssa;

import java.util.ArrayList;
import java.util.List;
import xyz.melodysky.ir.model.IrType;

/** Maps validated JVM field and method descriptors to SSA IR types. */
final class JvmToIrTypes {
    private JvmToIrTypes() {
    }

    static IrType fieldType(String descriptor) {
        ParsedType parsed = parseType(descriptor, 0, false);
        require(parsed.nextIndex() == descriptor.length(), descriptor, "trailing field descriptor content");
        return parsed.type();
    }

    static IrType returnType(String descriptor) {
        int end = methodParametersEnd(descriptor);
        ParsedType parsed = parseType(descriptor, end + 1, true);
        require(parsed.nextIndex() == descriptor.length(), descriptor, "trailing return descriptor content");
        return parsed.type();
    }

    static List<IrType> parameterTypes(String descriptor) {
        return parseParameters(descriptor).types();
    }

    static List<String> parameterDescriptors(String descriptor) {
        return parseParameters(descriptor).descriptors();
    }

    private static MethodParameters parseParameters(String descriptor) {
        int end = methodParametersEnd(descriptor);
        ArrayList<IrType> types = new ArrayList<>();
        ArrayList<String> descriptors = new ArrayList<>();
        int index = 1;
        while (index < end) {
            int start = index;
            ParsedType parsed = parseType(descriptor, index, false);
            require(parsed.nextIndex() <= end, descriptor, "parameter type crosses method descriptor boundary");
            types.add(parsed.type());
            descriptors.add(descriptor.substring(start, parsed.nextIndex()));
            index = parsed.nextIndex();
        }
        require(index == end, descriptor, "invalid method parameter descriptor");
        return new MethodParameters(types, descriptors);
    }

    private static int methodParametersEnd(String descriptor) {
        require(descriptor != null && !descriptor.isEmpty() && descriptor.charAt(0) == '(', descriptor,
                "method descriptor must start with '('");
        int end = descriptor.indexOf(')', 1);
        require(end >= 0, descriptor, "method descriptor is missing ')'");
        require(end + 1 < descriptor.length(), descriptor, "method descriptor is missing a return type");
        return end;
    }

    private static ParsedType parseType(String descriptor, int index, boolean allowVoid) {
        require(descriptor != null && index >= 0 && index < descriptor.length(), descriptor,
                "descriptor type is missing");
        char tag = descriptor.charAt(index);
        return switch (tag) {
            case 'V' -> {
                require(allowVoid, descriptor, "void is not a value type");
                yield new ParsedType(IrType.VOID, index + 1);
            }
            case 'I', 'Z', 'B', 'S', 'C' -> new ParsedType(IrType.I32, index + 1);
            case 'J' -> new ParsedType(IrType.I64, index + 1);
            case 'F' -> new ParsedType(IrType.F32, index + 1);
            case 'D' -> new ParsedType(IrType.F64, index + 1);
            case 'L' -> parseObjectType(descriptor, index);
            case '[' -> parseArrayType(descriptor, index);
            default -> throw invalid(descriptor, "unsupported descriptor type " + tag);
        };
    }

    private static ParsedType parseObjectType(String descriptor, int index) {
        int end = descriptor.indexOf(';', index + 1);
        require(end > index + 1, descriptor, "object descriptor is missing an internal name or ';'");
        return new ParsedType(IrType.REFERENCE, end + 1);
    }

    private static ParsedType parseArrayType(String descriptor, int index) {
        int componentIndex = index;
        while (componentIndex < descriptor.length() && descriptor.charAt(componentIndex) == '[') {
            componentIndex++;
        }
        require(componentIndex < descriptor.length(), descriptor, "array descriptor is missing a component type");
        ParsedType component = parseType(descriptor, componentIndex, false);
        return new ParsedType(IrType.REFERENCE, component.nextIndex());
    }

    private static void require(boolean condition, String descriptor, String reason) {
        if (!condition) {
            throw invalid(descriptor, reason);
        }
    }

    private static IllegalArgumentException invalid(String descriptor, String reason) {
        return new IllegalArgumentException("invalid JVM descriptor '" + descriptor + "': " + reason);
    }

    private record ParsedType(IrType type, int nextIndex) {
    }

    private record MethodParameters(List<IrType> types, List<String> descriptors) {
        private MethodParameters {
            types = List.copyOf(types);
            descriptors = List.copyOf(descriptors);
        }
    }
}
