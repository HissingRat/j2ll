package xyz.melodysky.diagnostic;

public enum DiagnosticSeverity {
    INFO("info"),
    WARNING("warning"),
    ERROR("error");

    private final String wireName;

    DiagnosticSeverity(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
