package xyz.melodysky.config;

import java.util.Objects;

public record PassConfig(boolean enabled, ProtectionIntensity intensity) {
    public PassConfig {
        Objects.requireNonNull(intensity, "intensity");
    }
}
