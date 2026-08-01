package xyz.melodysky.analysis.method;

public enum NativeMethodInternalizationReason {
    METHOD_INTERNALIZATION_ELIGIBLE(
            "all observed entries are final LLVM-native callers"),
    METHOD_INTERNALIZATION_DISABLED(
            "method internalization is disabled"),
    METHOD_INTERNALIZATION_WORLD_NOT_AUTHORIZED(
            "method internalization requires a closed-world or approved current-JAR-only scope"),
    METHOD_INTERNALIZATION_MULTI_RELEASE_OWNER(
            "the method owner has a multi-release counterpart"),
    METHOD_INTERNALIZATION_METHOD_KIND_UNSUPPORTED(
            "the method kind cannot be removed from the Java class"),
    METHOD_INTERNALIZATION_METHOD_ACCESS_UNSUPPORTED(
            "only private, protected and explicitly allowlisted public methods are eligible"),
    METHOD_INTERNALIZATION_PUBLIC_NOT_ALLOWLISTED(
            "public method removal requires an exact method allowlist entry"),
    METHOD_INTERNALIZATION_PUBLIC_INSTANCE_REQUIRES_DECLARED_CLOSED_WORLD(
            "public instance method removal requires a declared closed world"),
    METHOD_INTERNALIZATION_PUBLIC_INSTANCE_ANALYSIS_WORLD_INCOMPLETE(
            "public instance method removal requires a parse-complete hierarchy and call world"),
    METHOD_INTERNALIZATION_PUBLIC_EXTERNAL_ENTRY_POINT(
            "public JVM launcher or agent entry points are always retained"),
    METHOD_INTERNALIZATION_KNOWN_JVM_CALLBACK_ENTRY(
            "the method implements a known JVM or JDK library callback contract"),
    METHOD_INTERNALIZATION_PUBLIC_INTERFACE_METHOD(
            "public interface methods are not eligible for method removal"),
    METHOD_INTERNALIZATION_PUBLIC_INSTANCE_TARGET_NOT_EXACT(
            "public instance invocation does not resolve exactly to the removable method"),
    METHOD_INTERNALIZATION_FINAL_PATH_NOT_LLVM(
            "the final implementation is not the LLVM native path"),
    METHOD_INTERNALIZATION_NO_NATIVE_CALLER(
            "no native caller reaches the method"),
    METHOD_INTERNALIZATION_CALLER_NOT_NATIVE_LOWERED(
            "an observed caller does not have a final native implementation"),
    METHOD_INTERNALIZATION_CALLER_PATH_NOT_LLVM(
            "an observed caller is not implemented by the LLVM native path"),
    METHOD_INTERNALIZATION_INTERNAL_CALL_PATH_MISSING(
            "the final caller plan has no native direct or dispatch path for the call"),
    METHOD_INTERNALIZATION_CROSS_OWNER_INSTANCE_CALL(
            "instance methods require every observed caller to have the same owner"),
    METHOD_INTERNALIZATION_VIRTUAL_DISPATCH_NOT_EXACT(
            "virtual dispatch can reach another in-scope implementation"),
    METHOD_INTERNALIZATION_INVOKE_KIND_UNSUPPORTED(
            "the observed invocation kind cannot use an internal native entry"),
    METHOD_INTERNALIZATION_METHOD_HANDLE_REFERENCE(
            "a method handle or bootstrap constant references the method"),
    METHOD_INTERNALIZATION_REFLECTION_OBSERVER(
            "an in-scope reflection or method-handle lookup resolves the method"),
    METHOD_INTERNALIZATION_ENCLOSING_METHOD_REFERENCE(
            "class metadata names the method as an enclosing method");

    private final String description;

    NativeMethodInternalizationReason(String description) {
        this.description = description;
    }

    public String reasonCode() {
        return name();
    }

    public String description() {
        return description;
    }
}
