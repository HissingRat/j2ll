package xyz.melodysky.analysis.method;

import xyz.melodysky.diagnostic.DiagnosticCode;

/** Stable diagnostics for resolving exact public-method removal authorization. */
public final class PublicMethodInternalizationAllowListDiagnostics {
    public static final DiagnosticCode TARGET_NOT_FOUND = DiagnosticCode.of(
            "METHOD_INTERNALIZATION_PUBLIC_ALLOWLIST_TARGET_NOT_FOUND");
    public static final DiagnosticCode TARGET_AMBIGUOUS = DiagnosticCode.of(
            "METHOD_INTERNALIZATION_PUBLIC_ALLOWLIST_TARGET_AMBIGUOUS");
    public static final DiagnosticCode TARGET_NOT_PUBLIC_CODE = DiagnosticCode.of(
            "METHOD_INTERNALIZATION_PUBLIC_ALLOWLIST_TARGET_NOT_PUBLIC_CODE");

    private PublicMethodInternalizationAllowListDiagnostics() {
    }
}
