package xyz.melodysky.toolchain;

/** Closed catalog of metadata-free exception class/message pairs. */
enum HostJniLowSensitivityThrowLeaf {
    ARRAY_NULL(
            "java/lang/NullPointerException",
            "array is null"),
    STRING_RECEIVER_NULL(
            "java/lang/NullPointerException",
            "string receiver is null"),
    DIVIDE_BY_ZERO(
            "java/lang/ArithmeticException",
            "/ by zero"),
    REFLECTION_RECEIVER_NULL(
            "java/lang/NullPointerException",
            "reflection receiver is null"),
    VAR_HANDLE_RECEIVER_NULL(
            "java/lang/NullPointerException",
            "VarHandle receiver is null"),
    STRING_BUILDER_RECEIVER_NULL(
            "java/lang/NullPointerException",
            "StringBuilder receiver is null"),
    MONITOR_NULL(
            "java/lang/NullPointerException",
            "monitor is null"),
    THROWABLE_NULL(
            "java/lang/NullPointerException",
            "throwable is null"),
    FIELD_RECEIVER_NULL(
            "java/lang/NullPointerException",
            "field receiver is null"),
    CALL_RECEIVER_NULL(
            "java/lang/NullPointerException",
            "call receiver is null"),
    BYTE_ARRAY_BOUNDS(
            "java/lang/ArrayIndexOutOfBoundsException",
            "byte array index out of bounds"),
    SHORT_ARRAY_BOUNDS(
            "java/lang/ArrayIndexOutOfBoundsException",
            "short array index out of bounds"),
    CHAR_ARRAY_BOUNDS(
            "java/lang/ArrayIndexOutOfBoundsException",
            "char array index out of bounds"),
    INT_ARRAY_BOUNDS(
            "java/lang/ArrayIndexOutOfBoundsException",
            "int array index out of bounds"),
    LONG_ARRAY_BOUNDS(
            "java/lang/ArrayIndexOutOfBoundsException",
            "long array index out of bounds"),
    FLOAT_ARRAY_BOUNDS(
            "java/lang/ArrayIndexOutOfBoundsException",
            "float array index out of bounds"),
    DOUBLE_ARRAY_BOUNDS(
            "java/lang/ArrayIndexOutOfBoundsException",
            "double array index out of bounds"),
    OBJECT_ARRAY_BOUNDS(
            "java/lang/ArrayIndexOutOfBoundsException",
            "object array index out of bounds"),
    NEGATIVE_OBJECT_ARRAY_LENGTH(
            "java/lang/NegativeArraySizeException",
            "negative object array length"),
    CHECKCAST_FAILED(
            "java/lang/ClassCastException",
            "j2ll checkcast failed"),
    SUBSTRING_RECEIVER_NULL(
            "java/lang/NullPointerException",
            "substring receiver is null"),
    STRING_NULL(
            "java/lang/NullPointerException",
            "string is null"),
    CONSTRUCTOR_RECEIVER_NULL(
            "java/lang/NullPointerException",
            "constructor receiver is null"),
    TEMPORARY_ARRAY_ALLOCATION_FAILED(
            "java/lang/OutOfMemoryError",
            "native temporary array allocation failed"),
    NEGATIVE_ARGUMENT(
            "java/lang/IllegalArgumentException",
            "negative");

    private final String exceptionClass;
    private final String message;

    HostJniLowSensitivityThrowLeaf(
            String exceptionClass,
            String message) {
        this.exceptionClass = exceptionClass;
        this.message = message;
    }

    static HostJniLowSensitivityThrowLeaf find(
            String exceptionClass,
            String message) {
        for (HostJniLowSensitivityThrowLeaf leaf : values()) {
            if (leaf.exceptionClass.equals(exceptionClass)
                    && leaf.message.equals(message)) {
                return leaf;
            }
        }
        return null;
    }

    String exceptionClass() {
        return exceptionClass;
    }

    String message() {
        return message;
    }

    String identity() {
        return exceptionClass + '\0' + message;
    }
}
