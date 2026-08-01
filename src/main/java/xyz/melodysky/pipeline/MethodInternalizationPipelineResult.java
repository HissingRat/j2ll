package xyz.melodysky.pipeline;

import java.util.List;
import java.util.Objects;
import xyz.melodysky.analysis.method.NativeMethodInternalizationPlan;
import xyz.melodysky.analysis.world.WholeProgramAnalysisScope;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.report.ProtectionPassReport;

public record MethodInternalizationPipelineResult(
        NativeMethodInternalizationPlan plan,
        ProtectionPassReport protectionReport,
        List<Diagnostic> diagnostics,
        WholeProgramAnalysisScope analysisScope,
        boolean classPathAnalyzed) {
    public MethodInternalizationPipelineResult {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(protectionReport, "protectionReport");
        diagnostics = List.copyOf(
                Objects.requireNonNull(diagnostics, "diagnostics"));
        Objects.requireNonNull(analysisScope, "analysisScope");
    }
}
