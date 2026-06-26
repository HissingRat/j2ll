package xyz.melodysky.config;

public enum ProtectionIntensity {
    LIGHT("light"),
    NORMAL("normal"),
    STRONG("strong");

    private final String wireName;

    ProtectionIntensity(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static ProtectionIntensity parse(String value) {
        for (ProtectionIntensity intensity : values()) {
            if (intensity.wireName.equals(value)) {
                return intensity;
            }
        }
        throw new IllegalArgumentException("unsupported protection intensity: " + value);
    }
}
