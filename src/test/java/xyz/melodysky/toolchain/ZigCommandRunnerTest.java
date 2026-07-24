package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ZigCommandRunnerTest {
    @Test
    void drainsLargeStdoutAndStderrWhileTheProcessIsRunning() throws Exception {
        String executable = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? "java.exe"
                : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);

        ZigCommandResult result = ZigCommandRunner.process().run(
                List.of(
                        java.toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        OutputFloodMain.class.getName()),
                null,
                Map.of());

        assertEquals(0, result.exitCode(), result.combinedOutput());
        assertTrue(result.stdout().length() >= 256 * 1024);
        assertTrue(result.stderr().length() >= 256 * 1024);
    }

    public static final class OutputFloodMain {
        private OutputFloodMain() {
        }

        public static void main(String[] arguments) {
            String chunk = "x".repeat(1024);
            for (int index = 0; index < 256; index++) {
                System.out.print(chunk);
                System.err.print(chunk);
            }
        }
    }
}
