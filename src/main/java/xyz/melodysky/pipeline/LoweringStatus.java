package xyz.melodysky.pipeline;

import java.util.Locale;

public enum LoweringStatus {
    NATIVE_LOWERED("nativeLowered"),
    SKIPPED("skipped");

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
