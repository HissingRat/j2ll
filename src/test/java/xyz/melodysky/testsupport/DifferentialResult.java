package xyz.melodysky.testsupport;

import java.nio.file.Path;

public record DifferentialResult(
        JvmRunResult originalRun,
        JvmRunResult outputRun,
        Path outputJar,
        boolean outputArtifactPresent,
        String mode,
        String boundary) {
}
