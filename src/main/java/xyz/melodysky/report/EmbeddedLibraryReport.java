package xyz.melodysky.report;

import java.util.Objects;

public record EmbeddedLibraryReport(String target, String jarPath, String sha256) {
    public EmbeddedLibraryReport {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(jarPath, "jarPath");
        Objects.requireNonNull(sha256, "sha256");
    }
}
