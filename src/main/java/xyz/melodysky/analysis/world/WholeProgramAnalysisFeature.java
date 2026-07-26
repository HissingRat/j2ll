package xyz.melodysky.analysis.world;

import xyz.melodysky.config.ConfigDiagnostics;
import xyz.melodysky.diagnostic.DiagnosticCode;

public enum WholeProgramAnalysisFeature {
    FIELD_INTERNALIZATION(
            "fieldInternalization",
            true,
            ConfigDiagnostics.FIELD_INTERNALIZATION_REQUIRES_CLOSED_WORLD,
            "fieldInternalization normally requires CLOSED_WORLD. Continuing will analyze "
                    + "references only inside the current input JAR; configured classPath "
                    + "entries and external reflection/JNI/agent observers will be outside "
                    + "the analysis scope.");

    private final String displayName;
    private final boolean currentJarOnlySupported;
    private final DiagnosticCode diagnosticCode;
    private final String currentJarOnlyWarning;

    WholeProgramAnalysisFeature(
            String displayName,
            boolean currentJarOnlySupported,
            DiagnosticCode diagnosticCode,
            String currentJarOnlyWarning) {
        this.displayName = displayName;
        this.currentJarOnlySupported = currentJarOnlySupported;
        this.diagnosticCode = diagnosticCode;
        this.currentJarOnlyWarning = currentJarOnlyWarning;
    }

    public String displayName() {
        return displayName;
    }

    public boolean currentJarOnlySupported() {
        return currentJarOnlySupported;
    }

    public DiagnosticCode diagnosticCode() {
        return diagnosticCode;
    }

    public String currentJarOnlyPrompt() {
        return displayName + " requires CLOSED_WORLD, continue? (Y/N)";
    }

    public String currentJarOnlyWarning() {
        return currentJarOnlyWarning;
    }
}
