package xyz.melodysky.process;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SubprocessRegistryTest {

    @Test
    public void testRegistrationIsRemovedOnClose() throws Exception {
        SubprocessRegistry.destroyAllForTest();
        Path workspace = Files.createTempDirectory("subprocess-registry-test-");
        Process process = startSleepingJavaProcess(workspace, "SleeperOne");
        try (SubprocessRegistry.Registration registration = SubprocessRegistry.register(process)) {
            assertEquals(1, SubprocessRegistry.activeProcessCountForTest());
        } finally {
            SubprocessRegistry.destroyProcessTree(process);
        }

        assertEquals(0, SubprocessRegistry.activeProcessCountForTest());
    }

    @Test
    public void testDestroyProcessTreeStopsRegisteredProcess() throws Exception {
        SubprocessRegistry.destroyAllForTest();
        Path workspace = Files.createTempDirectory("subprocess-registry-test-");
        Process process = startSleepingJavaProcess(workspace, "SleeperTwo");
        try (SubprocessRegistry.Registration ignored = SubprocessRegistry.register(process)) {
            SubprocessRegistry.destroyProcessTree(process);
            process.waitFor();
            assertTrue(!process.isAlive());
            assertEquals(0, SubprocessRegistry.activeProcessCountForTest());
        } finally {
            SubprocessRegistry.destroyAllForTest();
        }
    }

    @Test
    public void testWindowsTaskkillCommandUsesTreeForceFlags() {
        assertEquals(List.of("taskkill", "/PID", "1234", "/T", "/F"), SubprocessRegistry.windowsTaskkillCommand(1234));
    }

    @Test
    public void testRequestShutdownNowMarksShutdownRequested() {
        SubprocessRegistry.destroyAllForTest();
        SubprocessRegistry.requestShutdownNow();
        assertTrue(SubprocessRegistry.isShutdownRequested());
        SubprocessRegistry.destroyAllForTest();
    }

    private Process startSleepingJavaProcess(Path workspace, String className) throws Exception {
        Path sourceFile = workspace.resolve(className + ".java");
        Files.writeString(sourceFile, """
                public class %s {
                    public static void main(String[] args) throws Exception {
                        Thread.sleep(30000L);
                    }
                }
                """.formatted(className), StandardCharsets.UTF_8);

        String javaHome = System.getProperty("java.home");
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path javac = Path.of(javaHome, "bin", windows ? "javac.exe" : "javac");
        Path java = Path.of(javaHome, "bin", windows ? "java.exe" : "java");

        Process compile = new ProcessBuilder(javac.toString(), sourceFile.getFileName().toString())
                .directory(workspace.toFile())
                .redirectErrorStream(true)
                .start();
        String compilerOutput = new String(compile.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (compile.waitFor() != 0) {
            throw new IllegalStateException("Failed to compile test sleeper: " + compilerOutput);
        }

        return new ProcessBuilder(List.of(java.toString(), "-cp", workspace.toString(), className))
                .directory(workspace.toFile())
                .redirectErrorStream(true)
                .start();
    }
}
