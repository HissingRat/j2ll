package xyz.melodysky.config;

import java.nio.file.Path;
import java.util.Objects;

public record SigningConfig(
        Path keystorePath,
        String storePasswordEnv,
        String keyAlias,
        String keyPasswordEnv,
        String tsaUrl) {
    public SigningConfig {
        Objects.requireNonNull(keystorePath, "keystorePath");
        Objects.requireNonNull(storePasswordEnv, "storePasswordEnv");
        Objects.requireNonNull(keyAlias, "keyAlias");
        Objects.requireNonNull(keyPasswordEnv, "keyPasswordEnv");
    }
}
