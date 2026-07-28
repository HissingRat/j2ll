package xyz.melodysky.testsupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class DifferentialHarness {
    public DifferentialResult compareOriginalToOutputJar(
            Path originalJar,
            Path outputJar,
            String mainClass) throws IOException, InterruptedException {
        return compareOriginalToOutputJar(
                originalJar,
                outputJar,
                mainClass,
                List.of());
    }

    public DifferentialResult compareOriginalToOutputJar(
            Path originalJar,
            Path outputJar,
            String mainClass,
            List<String> jvmArgs) throws IOException, InterruptedException {
        JvmRunner runner = new JvmRunner();
        JvmRunResult original = runner.run(
                originalJar,
                mainClass,
                jvmArgs,
                List.of());
        JvmRunResult output = runner.run(
                outputJar,
                mainClass,
                jvmArgs,
                List.of());
        return new DifferentialResult(
                original,
                output,
                outputJar,
                Files.exists(outputJar),
                "CHILD_JVM",
                "");
    }

    public DifferentialResult compareOriginalToArtifactLevelOutput(
            Path originalJar,
            Path outputJar,
            String mainClass) throws IOException, InterruptedException {
        JvmRunResult original = new JvmRunner().run(originalJar, mainClass, List.of());
        return new DifferentialResult(
                original,
                null,
                outputJar,
                Files.exists(outputJar),
                "ARTIFACT_LEVEL_ONLY",
                "native dynamic library build, loader execution, and RegisterNatives runtime binding are not connected yet");
    }
}
