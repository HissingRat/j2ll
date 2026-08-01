package xyz.melodysky.report;

public enum NativeMethodRetentionMode {
    REGISTERED_NATIVE("registeredNative"),
    INTERNAL_NATIVE_ONLY("internalNativeOnly"),
    JAVA_BYTECODE("javaBytecode");

    private final String wireName;

    NativeMethodRetentionMode(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
