package xyz.melodysky.pipeline;

import java.util.Locale;

public enum LoweringStatus {
    LOWERED("lowered"),
    HALF_LOWERED("halfLowered"),
    FRONTEND_SKIPPED("frontendSkipped"),
    NOT_APPLICABLE("notApplicable"),
    FAILED("failed"),
    EXCLUDED("excluded");

    private final String wireName;

    LoweringStatus(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static LoweringStatus fromWireName(String wireName) {
        for (LoweringStatus status : values()) {
            if (status.wireName.equals(wireName)) {
                return status;
            }
        }
        throw new IllegalArgumentException("unknown lowering status: " + wireName.toLowerCase(Locale.ROOT));
    }
}
