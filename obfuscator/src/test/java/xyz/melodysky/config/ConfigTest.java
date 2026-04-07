package xyz.melodysky.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

public class ConfigTest {

    @Test
    public void testLoadParsesStringObfuscationConfig() throws Exception {
        Path configPath = Files.createTempFile("j2ll-config-", ".json");
        try {
            Files.writeString(configPath, """
                    {
                      "jarFile": "input.jar",
                      "outputDirectory": "out",
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
        } finally {
            Files.deleteIfExists(configPath);
        }
    }

    @Test
    public void testResolvePlatformDefaultsToHotspot() {
        Config config = new Config();

        assertFalse(config.stringObfuscation.enabled);
        assertFalse(config.stringObfuscation.cacheStrings);
    }
}
