package xyz.melodysky.runtime;

public final class RuntimeAbi {
    public String llvmType(String abiType) {
        return switch (abiType) {
            case "void", "i32", "i64", "float", "double" -> abiType;
            case "ptr", "jobject", "jclass", "jarray", "jthrowable", "jstring" -> "ptr";
            default -> throw new IllegalArgumentException("unsupported runtime ABI type " + abiType);
        };
    }

    public String cType(String abiType) {
        return switch (abiType) {
            case "void" -> "void";
            case "i32" -> "int32_t";
            case "i64" -> "int64_t";
            case "float" -> "float";
            case "double" -> "double";
            case "ptr", "jobject" -> "jobject";
            case "jclass" -> "jclass";
            case "jarray" -> "jarray";
            case "jthrowable" -> "jthrowable";
            case "jstring" -> "jstring";
            default -> throw new IllegalArgumentException("unsupported runtime ABI type " + abiType);
        };
    }

    public boolean isJavaReference(String abiType) {
        return switch (abiType) {
            case "ptr", "jobject", "jclass", "jarray", "jthrowable", "jstring" -> true;
            default -> false;
        };
    }
}
