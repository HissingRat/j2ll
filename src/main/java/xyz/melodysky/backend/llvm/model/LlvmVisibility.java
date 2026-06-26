package xyz.melodysky.backend.llvm.model;

public enum LlvmVisibility {
    DEFAULT("default"),
    HIDDEN("hidden");

    private final String text;

    LlvmVisibility(String text) {
        this.text = text;
    }

    public String text() {
        return text;
    }
}
