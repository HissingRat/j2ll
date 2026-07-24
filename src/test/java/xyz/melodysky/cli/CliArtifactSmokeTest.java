package xyz.melodysky.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class CliArtifactSmokeTest {
    @Test
    void generatedCliJarRunsHelpAndVersion() throws Exception {
        Path jar = Path.of("build/cli/j2ll.jar");
        assertTrue(Files.isRegularFile(jar), "expected runnable CLI jar at " + jar);

        ProcessResult help = runJar(jar, "--help");
        ProcessResult version = runJar(jar, "--version");

        assertEquals(0, help.exitCode(), help.stderr());
        assertTrue(help.stdout().contains(
                "j2ll [--config <config.json>] [--validate | --dry-run] [--debug]"), help.stdout());
        assertEquals(0, version.exitCode(), version.stderr());
        assertTrue(version.stdout().startsWith("j2ll "), version.stdout());
    }

    @Test
    void distPackageContainsRunnableJarDocsAndValidExampleConfig() throws Exception {
        Path dist = Path.of("build/dist/j2ll");
        Path jar = dist.resolve("j2ll.jar");
        Path example = dist.resolve("docs/examples/minimal-config.json");
        assertTrue(Files.isRegularFile(jar), "expected distribution CLI jar at " + jar);
        assertTrue(Files.isRegularFile(example), "expected copied minimal config at " + example);
        assertTrue(Files.isRegularFile(dist.resolve("docs/samples/basic-cli-app.md")));

        ProcessResult version = runJar(jar, "--version");
        ProcessResult help = runJar(jar, "--help");
        ProcessResult validate = runJar(jar, "--validate", "--config", example.toString());

        assertEquals(0, version.exitCode(), version.stderr());
        assertEquals(0, help.exitCode(), help.stderr());
        assertEquals(0, validate.exitCode(), validate.stderr());
        assertTrue(validate.stdout().contains("config=ok"), validate.stdout());
    }

    private ProcessResult runJar(Path jar, String... arguments) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
        java.util.ArrayList<String> command = new java.util.ArrayList<>(List.of(
                java.toString(),
                "-jar",
                jar.toAbsolutePath().toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(false)
                .start();
        byte[] stdout = process.getInputStream().readAllBytes();
        byte[] stderr = process.getErrorStream().readAllBytes();
        int exitCode = process.waitFor();
        return new ProcessResult(
                exitCode,
                new String(stdout, StandardCharsets.UTF_8),
                new String(stderr, StandardCharsets.UTF_8));
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private record ProcessResult(int exitCode, String stdout, String stderr) {}
}
