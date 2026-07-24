package xyz.melodysky.cli;

import java.nio.file.Path;
import java.util.Objects;

public record CliOptions(
        CliMode mode,
        Path configPath,
        boolean debug,
        boolean helpRequested,
        boolean versionRequested) {
    public CliOptions {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(configPath, "configPath");
    }
}
