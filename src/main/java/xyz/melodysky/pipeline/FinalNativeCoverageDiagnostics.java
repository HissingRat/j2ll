package xyz.melodysky.pipeline;

import xyz.melodysky.diagnostic.DiagnosticCode;

public final class FinalNativeCoverageDiagnostics {
    public static final DiagnosticCode UNSUPPORTED_JVM_EXCEPTION_FLOW =
            DiagnosticCode.of("UNSUPPORTED_JVM_EXCEPTION_FLOW");
    public static final DiagnosticCode UNBOUNDED_JNI_LOCAL_REFERENCE_LIFETIME =
            DiagnosticCode.of("UNBOUNDED_JNI_LOCAL_REFERENCE_LIFETIME");
    public static final DiagnosticCode NATIVE_IMPLEMENTATION_UNAVAILABLE =
            DiagnosticCode.of("NATIVE_IMPLEMENTATION_UNAVAILABLE");

    private FinalNativeCoverageDiagnostics() {
    }
}
