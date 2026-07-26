package xyz.melodysky.pipeline;

import xyz.melodysky.diagnostic.DiagnosticCode;

public final class FinalNativeCoverageDiagnostics {
    public static final DiagnosticCode NATIVE_BACKEND_FALLBACK =
            DiagnosticCode.of("NATIVE_BACKEND_FALLBACK");
    public static final DiagnosticCode UNSUPPORTED_PROTECTED_JVM_EXCEPTION_FLOW =
            DiagnosticCode.of("UNSUPPORTED_PROTECTED_JVM_EXCEPTION_FLOW");
    public static final DiagnosticCode NATIVE_IMPLEMENTATION_UNAVAILABLE =
            DiagnosticCode.of("NATIVE_IMPLEMENTATION_UNAVAILABLE");

    private FinalNativeCoverageDiagnostics() {
    }
}
