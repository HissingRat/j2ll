package xyz.melodysky.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceLayoutTest {
    @TempDir
    Path temp;

    @Test
    void exposesNormalizedRootJarAndReportPaths() {
        Path normalizedRoot = temp.resolve("workspace");
        WorkspaceLayout layout = new WorkspaceLayout(temp.resolve("nested/../workspace"));

        assertEquals(normalizedRoot, layout.root());
        assertEquals(normalizedRoot.resolve("reports"), layout.reportsDirectory());
        assertEquals(normalizedRoot.resolve("input.jar"), layout.outputJar(temp.resolve("inputs/input.jar")));
        assertEquals(normalizedRoot.resolve("config-failed.jar"), layout.failedOutputJar());
    }

    @Test
    void createsWorkspaceDirectoriesWithoutOutputSubdirectory() throws Exception {
        WorkspaceLayout layout = new WorkspaceLayout(temp.resolve("workspace"));

        layout.createDirectories();

        assertTrue(Files.isDirectory(layout.reportsDirectory()));
        assertTrue(Files.isDirectory(layout.root().resolve("native")));
        assertTrue(Files.isDirectory(layout.root().resolve("intermediates/classes")));
        assertTrue(Files.isDirectory(layout.root().resolve("intermediates/runtime")));
        assertTrue(Files.isDirectory(layout.root().resolve("intermediates/dumps")));
        assertTrue(Files.isDirectory(layout.root().resolve("logs")));
        assertFalse(Files.exists(layout.root().resolve("output")));
    }
}
