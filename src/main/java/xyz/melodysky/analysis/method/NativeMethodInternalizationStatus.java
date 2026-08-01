package xyz.melodysky.analysis.method;

public enum NativeMethodInternalizationStatus {
    INTERNALIZED("internalized"),
    KEPT("kept");

    private final String wireName;

    NativeMethodInternalizationStatus(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
