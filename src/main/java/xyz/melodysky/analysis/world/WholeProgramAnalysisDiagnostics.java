package xyz.melodysky.analysis.world;

import xyz.melodysky.diagnostic.DiagnosticCode;

public final class WholeProgramAnalysisDiagnostics {
    public static final DiagnosticCode CURRENT_JAR_ONLY_USER_APPROVED =
            DiagnosticCode.of("WHOLE_PROGRAM_CURRENT_JAR_ONLY_USER_APPROVED");

    private WholeProgramAnalysisDiagnostics() {
    }
}
