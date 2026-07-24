package xyz.melodysky.progress;

public enum BuildStage {
    INPUT_INSPECTION("Inspecting input"),
    CLASS_PARSING("Parsing classes"),
    METHOD_SELECTION("Selecting methods"),
    PROGRAM_ANALYSIS("Analyzing program"),
    METHOD_LOWERING("Lowering and protecting methods"),
    NATIVE_PLANNING("Planning native implementations"),
    LLVM_EMISSION("Emitting LLVM"),
    INTERMEDIATE_WRITING("Writing intermediates"),
    TARGET_PREFLIGHT("Checking native targets"),
    NATIVE_BUILD("Building native libraries"),
    JAR_PACKAGING("Packaging output JAR"),
    ARTIFACT_AUDIT("Auditing artifacts"),
    REPORT_WRITING("Writing reports");

    private final String displayName;

    BuildStage(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
