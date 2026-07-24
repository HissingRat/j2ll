package xyz.melodysky.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import xyz.melodysky.config.ConfigLoader;

class CliConfigOverridesTest {
    @Test
    void debugEnablesEveryIntermediateWithoutMutatingLoadedConfig() throws Exception {
        JsonObject root = JsonParser.parseString(
                java.nio.file.Files.readString(Path.of("docs/examples/minimal-config.json")))
                .getAsJsonObject();
        JsonObject intermediates = root.getAsJsonObject("intermediates");
        intermediates.entrySet().forEach(entry -> entry.setValue(new com.google.gson.JsonPrimitive(false)));
        var original = new ConfigLoader().load(root, Path.of(".")).config().orElseThrow();

        var overridden = new CliConfigOverrides().applyDebug(original, true);

        assertFalse(original.intermediates().enabled());
        assertTrue(overridden.intermediates().enabled());
        assertTrue(overridden.intermediates().includeDebugDumps());
        assertTrue(overridden.intermediates().includePerClassIr());
        assertTrue(overridden.intermediates().includePerClassLlvm());
        assertTrue(overridden.intermediates().includePerClassC());
    }

    @Test
    void disabledDebugReturnsOriginalConfig() throws Exception {
        JsonObject root = JsonParser.parseString(
                java.nio.file.Files.readString(Path.of("docs/examples/minimal-config.json")))
                .getAsJsonObject();
        var original = new ConfigLoader().load(root, Path.of(".")).config().orElseThrow();

        assertTrue(new CliConfigOverrides().applyDebug(original, false) == original);
    }
}
