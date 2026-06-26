package xyz.melodysky.toolchain;

import java.util.Objects;

public record ZigCommandResult(int exitCode, String stdout, String stderr) {
    public ZigCommandResult {
        Objects.requireNonNull(stdout, "stdout");
        Objects.requireNonNull(stderr, "stderr");
    }

    public String combinedOutput() {
        if (stdout.isBlank()) {
            return stderr;
        }
        if (stderr.isBlank()) {
            return stdout;
        }
        return stdout + System.lineSeparator() + stderr;
    }
}
