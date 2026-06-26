package xyz.melodysky.analysis.hierarchy;

import xyz.melodysky.diagnostic.DiagnosticCode;

public final class HierarchyDiagnostics {
    public static final DiagnosticCode MISSING_EXTERNAL_CLASS = DiagnosticCode.of("MISSING_EXTERNAL_CLASS");
    public static final DiagnosticCode HIERARCHY_CYCLE = DiagnosticCode.of("HIERARCHY_CYCLE");
    public static final DiagnosticCode DUPLICATE_HIERARCHY_CLASS = DiagnosticCode.of("DUPLICATE_HIERARCHY_CLASS");

    private HierarchyDiagnostics() {
    }
}
