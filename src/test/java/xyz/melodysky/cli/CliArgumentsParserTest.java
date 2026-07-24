package xyz.melodysky.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CliArgumentsParserTest {
    private final CliArgumentsParser parser = new CliArgumentsParser();

    @Test
    void defaultsToBuildAndDefaultConfig() {
        CliOptions options = parseSuccessfully();

        assertEquals(CliMode.BUILD, options.mode());
        assertEquals(Path.of("Config.json"), options.configPath());
        assertFalse(options.debug());
        assertFalse(options.helpRequested());
        assertFalse(options.versionRequested());
    }

    @Test
    void parsesFlagsInAnyOrder() {
        CliOptions options = parseSuccessfully(
                "--debug",
                "--config", "configs/rewrite.json",
                "--dry-run");

        assertEquals(CliMode.DRY_RUN, options.mode());
        assertEquals(Path.of("configs/rewrite.json"), options.configPath());
        assertTrue(options.debug());
    }

    @Test
    void parsesValidateMode() {
        CliOptions options = parseSuccessfully("--validate", "--config", "release.json");

        assertEquals(CliMode.VALIDATE, options.mode());
        assertEquals(Path.of("release.json"), options.configPath());
    }

    @Test
    void repeatedBooleanFlagsAreIdempotent() {
        CliOptions options = parseSuccessfully("--debug", "--dry-run", "--debug", "--dry-run");

        assertEquals(CliMode.DRY_RUN, options.mode());
        assertTrue(options.debug());
    }

    @Test
    void repeatedConfigUsesLastValue() {
        CliOptions options = parseSuccessfully(
                "--config", "first.json",
                "--config", "second.json");

        assertEquals(Path.of("second.json"), options.configPath());
        assertEquals(CliMode.BUILD, options.mode());
    }

    @Test
    void validateAndDryRunAreMutuallyExclusive() {
        CliParseResult result = parser.parse(new String[] {"--validate", "--dry-run"});

        assertTrue(result.hasErrors());
        assertTrue(result.options().isEmpty());
        assertTrue(result.errors().contains("Options --validate and --dry-run are mutually exclusive"));
    }

    @Test
    void helpAndVersionCanEachBeRequestedAlone() {
        CliOptions help = parseSuccessfully("--help", "--help");
        CliOptions version = parseSuccessfully("--version", "--version");

        assertTrue(help.helpRequested());
        assertFalse(help.versionRequested());
        assertTrue(version.versionRequested());
        assertFalse(version.helpRequested());
    }

    @Test
    void helpAndVersionMustBeExclusive() {
        CliParseResult together = parser.parse(new String[] {"--help", "--version"});
        CliParseResult helpWithBuildOption = parser.parse(new String[] {"--help", "--debug"});
        CliParseResult versionWithConfig = parser.parse(new String[] {"--version", "--config", "config.json"});

        assertTrue(together.hasErrors());
        assertTrue(helpWithBuildOption.hasErrors());
        assertTrue(versionWithConfig.hasErrors());
    }

    @Test
    void rejectsMissingConfigValue() {
        CliParseResult atEnd = parser.parse(new String[] {"--config"});
        CliParseResult beforeFlag = parser.parse(new String[] {"--config", "--debug"});

        assertEquals("Missing value for --config", atEnd.errors().getFirst());
        assertEquals("Missing value for --config", beforeFlag.errors().getFirst());
        assertTrue(atEnd.options().isEmpty());
        assertTrue(beforeFlag.options().isEmpty());
    }

    @Test
    void rejectsUnknownFlagsAndPositionalArguments() {
        CliParseResult unknownFlag = parser.parse(new String[] {"--analyze"});
        CliParseResult positional = parser.parse(new String[] {"build"});

        assertEquals("Unknown argument: --analyze", unknownFlag.errors().getFirst());
        assertEquals("Unknown argument: build", positional.errors().getFirst());
    }

    private CliOptions parseSuccessfully(String... args) {
        CliParseResult result = parser.parse(args);
        assertFalse(result.hasErrors(), () -> "unexpected parse errors: " + result.errors());
        assertTrue(result.options().isPresent());
        return result.options().orElseThrow();
    }
}
