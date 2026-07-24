package xyz.melodysky.cli;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import xyz.melodysky.config.ConfigDiagnostics;
import xyz.melodysky.config.ConfigLoadResult;
import xyz.melodysky.config.ConfigLoader;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticStage;

/** Loads CLI configuration and preserves a report location even when validation fails. */
final class CliConfigResolver {
    ConfigLoadResult load(Path configPath) {
        try {
            return new ConfigLoader().load(configPath);
        } catch (IOException exception) {
            return failed(
                    ConfigDiagnostics.INVALID_PATH,
                    "cannot read config file " + configPath + ": " + exception.getMessage());
        } catch (RuntimeException exception) {
            return failed(
                    ConfigDiagnostics.INVALID_FIELD_VALUE,
                    "cannot parse config file " + configPath + ": " + exception.getMessage());
        }
    }

    Path outputDirectory(Path configPath, ConfigLoadResult loaded) {
        if (loaded.config().isPresent()) {
            return loaded.config().orElseThrow().outputDirectory();
        }
        Path baseDirectory = baseDirectory(configPath);
        try (Reader reader = Files.newBufferedReader(configPath)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonElement outputDirectory = root.get("outputDirectory");
            if (outputDirectory != null
                    && outputDirectory.isJsonPrimitive()
                    && outputDirectory.getAsJsonPrimitive().isString()
                    && !outputDirectory.getAsString().isBlank()) {
                Path configured = Path.of(outputDirectory.getAsString());
                return configured.isAbsolute()
                        ? configured.normalize()
                        : baseDirectory.resolve(configured).normalize();
            }
        } catch (IOException | RuntimeException ignored) {
            // A config diagnostic already explains the parse/read failure. Use a stable report fallback.
        }
        return baseDirectory.resolve("out").normalize();
    }

    private ConfigLoadResult failed(xyz.melodysky.diagnostic.DiagnosticCode code, String message) {
        return new ConfigLoadResult(
                Optional.empty(),
                List.of(Diagnostic.error(DiagnosticStage.CONFIG, code, message)));
    }

    private Path baseDirectory(Path configPath) {
        Path parent = configPath.getParent();
        return parent == null ? Path.of(".").toAbsolutePath().normalize() : parent;
    }
}
