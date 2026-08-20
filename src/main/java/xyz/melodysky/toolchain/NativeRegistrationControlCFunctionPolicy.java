package xyz.melodysky.toolchain;

/** C function attributes required to preserve registration control calls. */
final class NativeRegistrationControlCFunctionPolicy {
    static final String ATTRIBUTES =
            "__attribute__((noinline, disable_tail_calls))";

    private NativeRegistrationControlCFunctionPolicy() {}

    static String prototype(String declaration) {
        return declaration + " " + ATTRIBUTES + ";";
    }

    static String definitionHeader(String declaration) {
        return declaration + " {";
    }
}
