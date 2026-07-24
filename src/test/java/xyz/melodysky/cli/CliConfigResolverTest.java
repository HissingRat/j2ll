package xyz.melodysky.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliConfigResolverTest {
    @TempDir
    Path temp;

    @Test
    void invalidConfigStillUsesConfiguredOutputDirectoryForFailureReports() throws Exception {
        Path config = temp.resolve("broken.json");
        Files.writeString(config, """
                {
                  "jarFile": "input.jar",
                  "outputDirectory": "custom-output"
                }
                """);
        CliConfigResolver resolver = new CliConfigResolver();
        var loaded = resolver.load(config);

        assertTrue(loaded.hasErrors());
        assertEquals(temp.resolve("custom-output"), resolver.outputDirectory(config, loaded));
    }

    @Test
    void unreadableConfigUsesStableSiblingOutDirectory() {
        Path config = temp.resolve("missing.json");
        CliConfigResolver resolver = new CliConfigResolver();
        var loaded = resolver.load(config);

        assertTrue(loaded.hasErrors());
        assertEquals(temp.resolve("out"), resolver.outputDirectory(config, loaded));
    }
}
