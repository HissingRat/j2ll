package xyz.melodysky.analysis.world;

import java.util.Objects;
import xyz.melodysky.diagnostic.DiagnosticCode;

public record WholeProgramAnalysisRequirement(
        WholeProgramAnalysisFeature feature,
        DiagnosticCode diagnosticCode,
        String prompt,
        String warning) {
    public WholeProgramAnalysisRequirement {
        Objects.requireNonNull(feature, "feature");
        Objects.requireNonNull(diagnosticCode, "diagnosticCode");
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(warning, "warning");
    }
}
