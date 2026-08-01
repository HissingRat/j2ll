package xyz.melodysky.analysis.method;

import xyz.melodysky.diagnostic.DiagnosticCode;

/** Stable diagnostics for explicitly accepted public-method removal risks. */
public final class PublicMethodInternalizationDiagnostics {
    public static final DiagnosticCode UNRESOLVED_REFLECTION_RISK_ACCEPTED =
            DiagnosticCode.of(
                    "METHOD_INTERNALIZATION_PUBLIC_UNRESOLVED_REFLECTION_RISK_ACCEPTED");

    private PublicMethodInternalizationDiagnostics() {
    }
}
