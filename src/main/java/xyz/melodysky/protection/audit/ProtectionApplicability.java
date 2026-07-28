package xyz.melodysky.protection.audit;

/**
 * Explicit protection-pass applicability evidence.
 *
 * <p>{@code UNKNOWN} is required when the producing stage did not evaluate or
 * persist applicability. The audit never infers applicability from
 * {@code status=SKIPPED}.
 */
public enum ProtectionApplicability {
    APPLICABLE("applicable"),
    NOT_APPLICABLE("notApplicable"),
    UNKNOWN("unknown");

    private final String wireName;

    ProtectionApplicability(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
