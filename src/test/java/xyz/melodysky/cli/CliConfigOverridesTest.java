package xyz.melodysky.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import xyz.melodysky.config.ConfigLoader;
import xyz.melodysky.toolchain.NativeUnwindRetentionPolicy;
import xyz.melodysky.toolchain.NativeUnwindRetentionReason;
import xyz.melodysky.toolchain.TargetTriple;

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
        assertFalse(original.debugMode());
        assertFalse(original.protection().binary().retainUnwindInfo());
        assertTrue(overridden.intermediates().enabled());
        assertTrue(overridden.intermediates().includeDebugDumps());
        assertTrue(overridden.intermediates().includePerClassIr());
        assertTrue(overridden.intermediates().includePerClassLlvm());
        assertTrue(overridden.intermediates().includePerClassC());
        assertTrue(overridden.debugMode());
        assertFalse(overridden.protection().binary().retainUnwindInfo());
        var unwind = new NativeUnwindRetentionPolicy(
                        overridden.protection().binary().retainUnwindInfo(),
                        overridden.debugMode())
                .resolve(TargetTriple.LINUX_X64);
        assertTrue(unwind.effective());
        assertTrue(unwind.reason() == NativeUnwindRetentionReason.DEBUG_MODE);
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
