package xyz.melodysky.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class ConfigSchemaDocsTest {
    private static final Path DOCS = Path.of("docs");

    @Test
    void configSchemaTopLevelFieldsMatchLoaderContract() throws Exception {
        JsonObject schema = readJson(DOCS.resolve("config.schema.json"));
        Set<String> properties = schema.getAsJsonObject("properties").keySet();
        Set<String> required = strings(schema.getAsJsonArray("required"));

        assertEquals(Set.of(
                "schemaVersion",
                "jarFile",
                "classPath",
                "javaHome",
                "runtimeImage",
                "worldModel",
                "javaSupportTier",
                "fallbackMode",
                "outputDirectory",
                "whiteList",
                "blackList",
                "target",
                "libraryName",
                "embeddedLibraryDirectory",
                "signaturePolicy",
                "signing",
                "intermediates",
                "protection"), properties);
        assertFalse(required.contains("target"), "target remains optional and defaults to the current host");
        assertTrue(schema.get("additionalProperties").getAsBoolean(), "runtime warns on unknown fields instead of failing");
        assertEquals(Set.of("TIER_0", "TIER_1", "TIER_2", "TIER_3", "TIER_4", "TIER_5"),
                strings(schema.getAsJsonObject("properties")
                        .getAsJsonObject("javaSupportTier")
                        .getAsJsonArray("enum")));
        assertEquals("nativeEmbeddedClassBlob", schema.getAsJsonObject("properties")
                .getAsJsonObject("fallbackMode")
                .get("const")
                .getAsString());
    }

    @Test
    void documentedExampleConfigsLoadWithConfigLoader() throws Exception {
        ConfigLoader loader = new ConfigLoader();
        try (var paths = Files.list(DOCS.resolve("examples"))) {
            for (Path example : paths.filter(path -> path.toString().endsWith("-config.json")).sorted().toList()) {
                ConfigLoadResult result = loader.load(example);
                assertTrue(result.config().isPresent(), example + " diagnostics: " + result.diagnostics());
                assertFalse(result.hasErrors(), example + " diagnostics: " + result.diagnostics());
            }
        }
    }

    @Test
    void missingRequiredFieldIsRepresentedInSchemaAndRejectedByLoader() throws Exception {
        JsonObject schema = readJson(DOCS.resolve("config.schema.json"));
        assertTrue(strings(schema.getAsJsonArray("required")).contains("jarFile"));

        JsonObject example = readJson(DOCS.resolve("examples/minimal-config.json"));
        example.remove("jarFile");
        ConfigLoadResult result = new ConfigLoader().load(example, DOCS.resolve("examples"));

        assertTrue(result.hasErrors());
        assertTrue(result.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.code().value().equals("MISSING_REQUIRED_FIELD")
                        && diagnostic.message().contains("config.jarFile")), result.diagnostics().toString());
    }

    private JsonObject readJson(Path path) throws Exception {
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }

    private Set<String> strings(com.google.gson.JsonArray array) {
        TreeSet<String> result = new TreeSet<>();
        array.forEach(element -> result.add(element.getAsString()));
        return result;
    }
}
