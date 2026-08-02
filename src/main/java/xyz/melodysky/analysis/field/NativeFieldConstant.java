package xyz.melodysky.analysis.field;

import java.util.Objects;
import java.util.Optional;
import xyz.melodysky.frontend.classfile.ParsedField;

/**
 * Exact, immutable ConstantValue payload approved for SSA folding.
 *
 * <p>Floating-point payloads are stored as raw bits so NaN payloads and
 * negative zero remain distinguishable. Narrow integer descriptors are
 * normalized exactly as JVM field initialization does.</p>
 */
public record NativeFieldConstant(
        String descriptor,
        long scalarBits,
        Optional<String> stringValue) {
    public NativeFieldConstant {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(stringValue, "stringValue");
        if (descriptor.equals("Ljava/lang/String;")) {
            if (stringValue.isEmpty() || scalarBits != 0L) {
                throw new IllegalArgumentException(
                        "String field constants require only a String payload");
            }
        } else if (stringValue.isPresent() || !isPrimitiveDescriptor(descriptor)) {
            throw new IllegalArgumentException(
                    "unsupported native field constant descriptor: " + descriptor);
        } else if (!validScalarBits(descriptor, scalarBits)) {
            throw new IllegalArgumentException(
                    "non-canonical native field constant bits for descriptor: "
                            + descriptor);
        }
    }

    public static Optional<NativeFieldConstant> from(ParsedField field) {
        Objects.requireNonNull(field, "field");
        return from(field.descriptor(), field.constantValue());
    }

    public static Optional<NativeFieldConstant> from(
            String descriptor,
            Object value) {
        if (descriptor == null || value == null) {
            return Optional.empty();
        }
        return switch (descriptor) {
            case "Z" -> value instanceof Integer integer
                    ? Optional.of(scalar(descriptor, integer & 1))
                    : Optional.empty();
            case "B" -> value instanceof Integer integer
                    ? Optional.of(scalar(descriptor, (byte) integer.intValue()))
                    : Optional.empty();
            case "S" -> value instanceof Integer integer
                    ? Optional.of(scalar(descriptor, (short) integer.intValue()))
                    : Optional.empty();
            case "C" -> value instanceof Integer integer
                    ? Optional.of(scalar(descriptor, (char) integer.intValue()))
                    : Optional.empty();
            case "I" -> value instanceof Integer integer
                    ? Optional.of(scalar(descriptor, integer))
                    : Optional.empty();
            case "J" -> value instanceof Long longValue
                    ? Optional.of(scalar(descriptor, longValue))
                    : Optional.empty();
            case "F" -> value instanceof Float floatValue
                    ? Optional.of(scalar(
                            descriptor,
                            Integer.toUnsignedLong(Float.floatToRawIntBits(floatValue))))
                    : Optional.empty();
            case "D" -> value instanceof Double doubleValue
                    ? Optional.of(scalar(
                            descriptor,
                            Double.doubleToRawLongBits(doubleValue)))
                    : Optional.empty();
            case "Ljava/lang/String;" -> value instanceof String string
                    ? Optional.of(new NativeFieldConstant(
                            descriptor,
                            0L,
                            Optional.of(string)))
                    : Optional.empty();
            default -> Optional.empty();
        };
    }

    public boolean stringConstant() {
        return stringValue.isPresent();
    }

    public int intValue() {
        if (!descriptor.equals("Z")
                && !descriptor.equals("B")
                && !descriptor.equals("S")
                && !descriptor.equals("C")
                && !descriptor.equals("I")) {
            throw new IllegalStateException("field constant is not an i32 value");
        }
        return (int) scalarBits;
    }

    public long longValue() {
        requireDescriptor("J");
        return scalarBits;
    }

    public float floatValue() {
        requireDescriptor("F");
        return Float.intBitsToFloat((int) scalarBits);
    }

    public double doubleValue() {
        requireDescriptor("D");
        return Double.longBitsToDouble(scalarBits);
    }

    public String requiredStringValue() {
        return stringValue.orElseThrow(() ->
                new IllegalStateException("field constant is not a String value"));
    }

    public boolean matchesClassfileValue(Object value) {
        return from(descriptor, value).map(this::equals).orElse(false);
    }

    private void requireDescriptor(String expected) {
        if (!descriptor.equals(expected)) {
            throw new IllegalStateException(
                    "field constant is not a " + expected + " value");
        }
    }

    private static NativeFieldConstant scalar(String descriptor, long bits) {
        return new NativeFieldConstant(descriptor, bits, Optional.empty());
    }

    private static boolean isPrimitiveDescriptor(String descriptor) {
        return descriptor.length() == 1
                && "ZBSCIJFD".indexOf(descriptor.charAt(0)) >= 0;
    }

    private static boolean validScalarBits(String descriptor, long bits) {
        return switch (descriptor) {
            case "Z" -> bits == 0L || bits == 1L;
            case "B" -> bits == (byte) bits;
            case "S" -> bits == (short) bits;
            case "C" -> bits >= 0L && bits <= Character.MAX_VALUE;
            case "I" -> bits == (int) bits;
            case "F" -> (bits & 0xffffffff00000000L) == 0L;
            case "J", "D" -> true;
            default -> false;
        };
    }
}
