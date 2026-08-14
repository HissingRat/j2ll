package xyz.melodysky.backend.llvm.model;

/** Closed LLVM function-attribute set emitted by the structured model. */
public enum LlvmFunctionAttribute {
    NOINLINE("noinline");

    private final String text;

    LlvmFunctionAttribute(String text) {
        this.text = text;
    }

    public String text() {
        return text;
    }
}
