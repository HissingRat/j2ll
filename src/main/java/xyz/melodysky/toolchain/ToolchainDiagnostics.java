package xyz.melodysky.toolchain;

import xyz.melodysky.diagnostic.DiagnosticCode;

public final class ToolchainDiagnostics {
    public static final DiagnosticCode ZIG_TARGET_PREFLIGHT =
            DiagnosticCode.of("ZIG_TARGET_PREFLIGHT");
    public static final DiagnosticCode ZIG_TARGET_UNBUILDABLE =
            DiagnosticCode.of("ZIG_TARGET_UNBUILDABLE");
    public static final DiagnosticCode SYMBOL_AUDIT_FAILED =
            DiagnosticCode.of("SYMBOL_AUDIT_FAILED");

    private ToolchainDiagnostics() {
    }
}
