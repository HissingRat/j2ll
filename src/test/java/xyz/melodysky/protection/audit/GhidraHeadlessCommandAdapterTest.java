package xyz.melodysky.protection.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GhidraHeadlessCommandAdapterTest {
    @TempDir
    Path temp;

    @Test
    void remainsOptionalAndBuildsStableGatedCommandWhenInstalled()
            throws Exception {
        Path missingHome = temp.resolve("missing");
        GhidraHeadlessRequest missing = request(missingHome);
        GhidraHeadlessCommandAdapter adapter =
                new GhidraHeadlessCommandAdapter();
        assertTrue(adapter.command(missing).isEmpty());

        Path home = temp.resolve("ghidra");
        Path support = Files.createDirectories(home.resolve("support"));
        String executableName = isWindows()
                ? "analyzeHeadless.bat"
                : "analyzeHeadless";
        Files.writeString(support.resolve(executableName), "");
        GhidraHeadlessRequest installed = request(home);

        List<String> command = adapter.command(installed).orElseThrow();

        assertEquals(
                support.resolve(executableName).toAbsolutePath().normalize().toString(),
                command.get(0));
        assertTrue(command.contains("-import"));
        assertTrue(command.contains("-scriptPath"));
        assertTrue(command.contains("-postScript"));
        assertTrue(command.contains("CollectMetrics.py"));
        assertEquals("-deleteProject", command.get(command.size() - 1));
    }

    private GhidraHeadlessRequest request(Path home) {
        return new GhidraHeadlessRequest(
                home,
                temp.resolve("projects"),
                "j2ll-audit",
                temp.resolve("native.dll"),
                temp.resolve("scripts"),
                "CollectMetrics.py",
                List.of(temp.resolve("metrics.json").toString()));
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
    }
}
