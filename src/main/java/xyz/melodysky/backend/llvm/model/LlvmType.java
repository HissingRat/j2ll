package xyz.melodysky.backend.llvm.model;

public enum LlvmType {
    VOID("void"),
    I1("i1"),
    I32("i32"),
    I64("i64"),
    F32("float"),
    F64("double"),
    PTR("ptr");

    private final String text;

    LlvmType(String text) {
        this.text = text;
    }

    public String text() {
        return text;
    }
}
