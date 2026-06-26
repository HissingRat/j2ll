package xyz.melodysky.dump;

public enum DumpKind {
    CLASSFILE("classfile"),
    CFG("cfg"),
    HIERARCHY("hierarchy"),
    CALLGRAPH("callgraph"),
    RUNTIME_ANALYSIS("runtime-analysis"),
    SSA("ssa"),
    OPTIMIZED("optimized"),
    PROTECTION("protection"),
    FALLBACK("fallback"),
    LLVM_MODEL("llvm-model"),
    LLVM_PROTECTION("llvm-protection"),
    LLVM("llvm"),
    NATIVE_LINK("native-link"),
    SYMBOL_AUDIT("symbol-audit"),
    PACKAGING("packaging");

    private final String wireName;

    DumpKind(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
