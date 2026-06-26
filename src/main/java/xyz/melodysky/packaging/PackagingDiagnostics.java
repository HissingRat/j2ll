package xyz.melodysky.packaging;

import xyz.melodysky.diagnostic.DiagnosticCode;

public final class PackagingDiagnostics {
    public static final DiagnosticCode STUB_REWRITE_NOT_IMPLEMENTED =
            DiagnosticCode.of("STUB_REWRITE_NOT_IMPLEMENTED");
    public static final DiagnosticCode NATIVE_ORIGINAL_REWRITE_FAILED =
            DiagnosticCode.of("NATIVE_ORIGINAL_REWRITE_FAILED");

    private PackagingDiagnostics() {
    }
}
