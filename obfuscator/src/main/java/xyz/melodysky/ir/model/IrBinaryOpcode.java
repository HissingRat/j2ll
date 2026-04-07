package xyz.melodysky.ir.model;

public enum IrBinaryOpcode {
    ADD("add"),
    SUB("sub"),
    MUL("mul"),
    DIV("div"),
    REM("rem"),
    AND("and"),
    OR("or"),
    XOR("xor"),
    SHL("shl"),
    SHR("shr"),
    USHR("ushr");

    private final String mnemonic;

    IrBinaryOpcode(String mnemonic) {
        this.mnemonic = mnemonic;
    }

    public String mnemonic() {
        return mnemonic;
    }
}
