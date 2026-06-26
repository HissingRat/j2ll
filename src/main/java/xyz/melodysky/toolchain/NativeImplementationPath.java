package xyz.melodysky.toolchain;

public enum NativeImplementationPath {
    LLVM_NATIVE_PATH("LLVM_NATIVE_PATH"),
    TEMPLATE_JNI_PATH("TEMPLATE_JNI_PATH");

    private final String wireName;

    NativeImplementationPath(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
