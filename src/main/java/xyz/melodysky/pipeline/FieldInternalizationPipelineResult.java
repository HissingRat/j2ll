package xyz.melodysky.pipeline;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import xyz.melodysky.analysis.field.NativeFieldInternalizationPlan;
import xyz.melodysky.analysis.world.WholeProgramAnalysisScope;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.report.ProtectionPassReport;

public record FieldInternalizationPipelineResult(
        NativeFieldInternalizationPlan plan,
        Map<String, IrMethod> methods,
        ProtectionPassReport protectionReport,
        List<Diagnostic> diagnostics,
        WholeProgramAnalysisScope analysisScope,
        boolean classPathAnalyzed) {
    public FieldInternalizationPipelineResult {
        methods = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(methods));
        diagnostics = List.copyOf(diagnostics);
        java.util.Objects.requireNonNull(analysisScope, "analysisScope");
    }
}
