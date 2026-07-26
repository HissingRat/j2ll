package xyz.melodysky.analysis.world;

public enum WholeProgramAnalysisScope {
    NOT_REQUIRED("notRequired"),
    DECLARED_CLOSED_WORLD("declaredClosedWorld"),
    CURRENT_JAR_ONLY_USER_APPROVED("currentJarOnlyUserApproved"),
    UNAVAILABLE("unavailable");

    private final String wireName;

    WholeProgramAnalysisScope(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public boolean analyzesClasspath() {
        return this == DECLARED_CLOSED_WORLD;
    }

    public boolean permitsWholeProgramTransform() {
        return this == DECLARED_CLOSED_WORLD || this == CURRENT_JAR_ONLY_USER_APPROVED;
    }
}
