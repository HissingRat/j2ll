package xyz.melodysky.config;

public enum ProtectionSeedMode {
    RANDOMIZED("randomized"),
    REPRODUCIBLE("reproducible");

    private final String wireName;

    ProtectionSeedMode(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
