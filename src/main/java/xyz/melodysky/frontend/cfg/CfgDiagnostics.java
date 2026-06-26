package xyz.melodysky.frontend.cfg;

import xyz.melodysky.diagnostic.DiagnosticCode;

public final class CfgDiagnostics {
    public static final DiagnosticCode METHOD_HAS_NO_CODE = DiagnosticCode.of("METHOD_HAS_NO_CODE");
    public static final DiagnosticCode CFG_MISSING_ENTRY = DiagnosticCode.of("CFG_MISSING_ENTRY");
    public static final DiagnosticCode CFG_BAD_EDGE_TARGET = DiagnosticCode.of("CFG_BAD_EDGE_TARGET");
    public static final DiagnosticCode CFG_BAD_HANDLER_TARGET = DiagnosticCode.of("CFG_BAD_HANDLER_TARGET");
    public static final DiagnosticCode CFG_OVERLAPPING_BLOCK = DiagnosticCode.of("CFG_OVERLAPPING_BLOCK");
    public static final DiagnosticCode CFG_DUPLICATE_BLOCK = DiagnosticCode.of("CFG_DUPLICATE_BLOCK");

    private CfgDiagnostics() {
    }
}
