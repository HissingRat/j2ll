package xyz.melodysky.backend.llvm.model;

public enum LlvmLinkage {
    INTERNAL("internal"),
    EXTERNAL("external");

    private final String text;

    LlvmLinkage(String text) {
        this.text = text;
    }

    public String text() {
        return text;
    }
}
