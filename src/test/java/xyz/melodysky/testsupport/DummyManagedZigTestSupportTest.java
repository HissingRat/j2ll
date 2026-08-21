package xyz.melodysky.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.melodysky.toolchain.J2llHomeResolver;

class DummyManagedZigTestSupportTest {
    @TempDir
    Path temp;

    @Test
    void explicitHomeWinsWithoutTouchingAutomaticCache() throws Exception {
        Path explicit = temp.resolve("explicit");
        Path executable = explicit.resolve("zig").resolve(isWindows() ? "zig.exe" : "zig");
        Files.createDirectories(executable.getParent());
        Files.writeString(executable, "");
        Path cache = temp.resolve("cache");

        try (AutoCloseable ignored = DummyManagedZigTestSupport.use(explicit, cache, true)) {
            assertEquals(explicit.toAbsolutePath().normalize().toString(),
                    System.getProperty(J2llHomeResolver.OVERRIDE_PROPERTY));
            assertFalse(Files.exists(cache));
        }
    }

    @Test
    void offlineModeWithoutCacheFailsWithoutPublishingArchive() {
        Path cache = temp.resolve("cache");

        IOException error = assertThrows(
                IOException.class,
                () -> DummyManagedZigTestSupport.use(null, cache, true));

        assertTrue(error.getMessage().contains("failed to download managed Zig 0.15.2"));
        assertTrue(error.getCause().getMessage().contains("Gradle offline mode"));
        assertTrue(Files.exists(cache));
        try (var paths = Files.walk(cache)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().endsWith(".downloading")));
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
