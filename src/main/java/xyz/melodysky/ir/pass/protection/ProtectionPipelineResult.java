package xyz.melodysky.ir.pass.protection;

import java.util.List;
import java.util.Objects;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.report.ProtectionPassReport;

public record ProtectionPipelineResult(
        IrMethod method,
        List<Diagnostic> diagnostics,
        List<ProtectionPassReport> reports) {
    public ProtectionPipelineResult {
        Objects.requireNonNull(method, "method");
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        reports = List.copyOf(Objects.requireNonNull(reports, "reports"));
    }
}
