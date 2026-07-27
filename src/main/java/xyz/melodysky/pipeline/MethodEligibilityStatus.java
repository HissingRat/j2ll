package xyz.melodysky.pipeline;

/**
 * Selector audit disposition for methods outside the Code-bearing lowering
 * result set.
 */
public enum MethodEligibilityStatus {
    INELIGIBLE("ineligible"),
    EXCLUDED("excluded");

    private final String wireName;

    MethodEligibilityStatus(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
