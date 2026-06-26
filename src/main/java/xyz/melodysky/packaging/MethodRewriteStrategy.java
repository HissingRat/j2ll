package xyz.melodysky.packaging;

public enum MethodRewriteStrategy {
    NATIVE_ORIGINAL("nativeOriginal"),
    CONSTRUCTOR_STUB("constructorStub"),
    CLASS_INITIALIZER_STUB("classInitializerStub"),
    INTERFACE_METHOD_STUB("interfaceMethodStub"),
    NOT_APPLICABLE("notApplicable");

    private final String wireName;

    MethodRewriteStrategy(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
