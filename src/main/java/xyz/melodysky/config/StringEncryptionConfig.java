package xyz.melodysky.config;

import java.util.Objects;

public record StringEncryptionConfig(boolean enabled, ProtectionIntensity intensity, boolean cacheStrings) {
    public StringEncryptionConfig {
        Objects.requireNonNull(intensity, "intensity");
    }
}
