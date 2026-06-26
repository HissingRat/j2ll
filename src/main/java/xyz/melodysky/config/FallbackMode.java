package xyz.melodysky.config;

public enum FallbackMode {
    NATIVE_EMBEDDED_CLASS_BLOB("nativeEmbeddedClassBlob");

    private final String wireName;

    FallbackMode(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static FallbackMode parse(String value) {
        for (FallbackMode mode : values()) {
            if (mode.wireName.equals(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("unsupported fallback mode: " + value);
    }
}
