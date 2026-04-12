package xyz.melodysky.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class ConfigTest {

    @Test
    public void testLoadParsesStringObfuscationConfigAndMaxShardMb() throws Exception {
        Path configPath = Files.createTempFile("j2ll-config-", ".json");
        try {
            Files.writeString(configPath, """
                    {
                      "jarFile": "input.jar",
                      "outputDirectory": "out",
                      "maxShardMB": 16,
                      "stringObfuscation": {
                        "enabled": true,
                        "cacheStrings": false
                      },
                      "target": {
                        "windowsX64": true,
                        "windowsArm64": false,
                        "linuxX64": false,
                        "linuxArm64": false,
                        "macosX64": false,
                        "macosArm64": false
                      }
                    }
                    """);

            Config config = Config.load(configPath);

            org.junit.jupiter.api.Assertions.assertTrue(config.stringObfuscation.enabled);
            assertFalse(config.stringObfuscation.cacheStrings);
            assertEquals(16, config.maxShardMB);
            assertEquals(16 * 1024 * 1024, config.getMaxShardBytes());
        } finally {
            Files.deleteIfExists(configPath);
        }
    }

    @Test
    public void testLoadRejectsNonPositiveMaxShardMb() throws Exception {
        Path configPath = Files.createTempFile("j2ll-config-", ".json");
        try {
            Files.writeString(configPath, """
                    {
                      "jarFile": "input.jar",
                      "outputDirectory": "out",
                      "maxShardMB": 0,
                      "target": {
                        "windowsX64": true,
                        "windowsArm64": false,
                        "linuxX64": false,
                        "linuxArm64": false,
                        "macosX64": false,
                        "macosArm64": false
                      }
                    }
                    """);

            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> Config.load(configPath)
            );
        } finally {
            Files.deleteIfExists(configPath);
        }
    }

    @Test
    public void testResolvePlatformDefaultsToHotspot() {
        Config config = new Config();

        assertFalse(config.stringObfuscation.enabled);
        assertFalse(config.stringObfuscation.cacheStrings);
        assertNull(config.maxShardMB);
        assertNull(config.getMaxShardBytes());
    }
}
