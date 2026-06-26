package xyz.melodysky.diagnostic;

public enum DiagnosticStage {
    CONFIG,
    INPUT_DISCOVERY,
    PARSE,
    CFG,
    HIERARCHY,
    CALL_GRAPH,
    RUNTIME_ANALYSIS,
    LOWERING,
    VALIDATION,
    OPTIMIZATION,
    PROTECTION,
    LLVM_MODEL,
    LLVM_PROTECTION,
    LLVM_EMISSION,
    NATIVE_LINK,
    SYMBOL_AUDIT,
    PACKAGING
}
