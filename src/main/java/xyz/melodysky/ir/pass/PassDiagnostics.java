package xyz.melodysky.ir.pass;

import xyz.melodysky.diagnostic.DiagnosticCode;

public final class PassDiagnostics {
    public static final DiagnosticCode PASS_VALIDATION_FAILED = DiagnosticCode.of("PASS_VALIDATION_FAILED");
    public static final DiagnosticCode PROTECTION_PASS_NOT_IMPLEMENTED =
            DiagnosticCode.of("PROTECTION_PASS_NOT_IMPLEMENTED");
    public static final DiagnosticCode PROTECTION_PASS_NOT_APPLICABLE =
            DiagnosticCode.of("PROTECTION_PASS_NOT_APPLICABLE");
    public static final DiagnosticCode PROTECTION_MONITOR_SENSITIVE_SKIP =
            DiagnosticCode.of("PROTECTION_MONITOR_SENSITIVE_SKIP");

    private PassDiagnostics() {
    }
}
