package xyz.melodysky.ir.model;

public enum IrCompareOpcode {
    EQ("eq"),
    NE("ne"),
    LT("lt"),
    LE("le"),
    GT("gt"),
    GE("ge");

    private final String mnemonic;

    IrCompareOpcode(String mnemonic) {
        this.mnemonic = mnemonic;
    }

    public String mnemonic() {
        return mnemonic;
    }
}
