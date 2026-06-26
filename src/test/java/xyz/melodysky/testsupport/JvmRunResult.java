package xyz.melodysky.testsupport;

public record JvmRunResult(
        int exitCode,
        String stdout,
        String stderr) {
}
