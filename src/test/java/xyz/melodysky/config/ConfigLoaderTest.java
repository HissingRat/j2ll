package xyz.melodysky.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import xyz.melodysky.toolchain.TargetTriple;

class ConfigLoaderTest {
    @Test
    void loadsFullSchemaV1AndDerivesNullableSeed() {
        JsonObject json = JsonParser.parseString(baseJson()).getAsJsonObject();

        ConfigLoadResult result = new ConfigLoader().load(json, Path.of("/cfg"));

        assertFalse(result.hasErrors());
        ResolvedConfig config = result.config().orElseThrow();
        assertEquals(JavaSupportTier.TIER_5, config.javaSupportTier());
        assertEquals(Path.of("/cfg/input.jar"), config.jarFile());
        assertEquals(TargetTriple.LINUX_X64, config.targets().get(0));
        assertEquals("native0", config.embeddedLibraryDirectory());
        assertEquals(SignaturePolicy.FAIL, config.signaturePolicy());
        assertTrue(config.intermediates().includePerClassLlvm());
        assertTrue(config.protection().ir().constantEncryption().enabled());
        assertNotNull(config.protection().seed());
        assertEquals(16, config.protection().seed().length());
    }

    @Test
    void defaultsMissingTargetToCurrentHostTarget() {
        JsonObject json = JsonParser.parseString(baseJson().replace("""
                  "target": {
                    "windowsX64": false,
                    "windowsArm64": false,
                    "linuxX64": true,
                    "linuxArm64": false,
                    "macosX64": false,
                    "macosArm64": false
                  },
                """, ""))
                .getAsJsonObject();

        ConfigLoadResult result = new ConfigLoader().load(json, Path.of("/cfg"));

        assertFalse(result.hasErrors(), result.diagnostics().toString());
        assertEquals(1, result.config().orElseThrow().targets().size());
    }

    @Test
    void warnsForUnknownTopLevelAndNestedFields() {
        JsonObject json = JsonParser.parseString(baseJson()
                        .replace("\"protection\": {", "\"extra\": true, \"protection\": {")
                        .replace("\"fakeBranches\": {", "\"unknownIrField\": false, \"fakeBranches\": {"))
                .getAsJsonObject();

        ConfigLoadResult result = new ConfigLoader().load(json, Path.of("/cfg"));

        assertFalse(result.hasErrors());
        assertEquals(2, result.diagnostics().stream()
                .filter(diagnostic -> diagnostic.code().equals(ConfigDiagnostics.UNKNOWN_FIELD))
                .count());
    }

    @Test
    void rejectsTier6JavaSupportTier() {
        JsonObject json = JsonParser.parseString(baseJson().replace("\"javaSupportTier\": \"TIER_5\"", "\"javaSupportTier\": \"TIER_6\""))
                .getAsJsonObject();

        ConfigLoadResult result = new ConfigLoader().load(json, Path.of("/cfg"));

        assertTrue(result.hasErrors());
        assertTrue(result.config().isEmpty());
        assertTrue(result.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.code().equals(ConfigDiagnostics.INVALID_FIELD_VALUE)
                        && diagnostic.message().contains("unsupported javaSupportTier: TIER_6")));
    }

    @Test
    void rejectsMissingNestedRequiredFields() {
        JsonObject json = JsonParser.parseString(baseJson().replace("\"includePerClassC\": true", "\"x\": true"))
                .getAsJsonObject();

        ConfigLoadResult result = new ConfigLoader().load(json, Path.of("/cfg"));

        assertTrue(result.hasErrors());
        assertTrue(result.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.code().equals(ConfigDiagnostics.MISSING_REQUIRED_FIELD)
                        && diagnostic.message().contains("intermediates.includePerClassC")));
    }

    @Test
    void rejectsJarPathThatIsNotJar() {
        JsonObject json = JsonParser.parseString(baseJson().replace("\"jarFile\": \"input.jar\"", "\"jarFile\": \"input.txt\""))
                .getAsJsonObject();

        ConfigLoadResult result = new ConfigLoader().load(json, Path.of("/cfg"));

        assertTrue(result.hasErrors());
        assertTrue(result.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.code().equals(ConfigDiagnostics.INVALID_PATH)
                        && diagnostic.message().contains("jarFile")));
    }

    @Test
    void rejectsStrictSelectorValidationFailures() {
        JsonObject json = JsonParser.parseString(baseJson().replace("\"whiteList\": []", """
                "whiteList": [
                    "my.pkg/Foo",
                    "my/pkg/Foo#doIt!(V)V",
                    "my/pkg/Foo#do-It!()V"
                  ]"""))
                .getAsJsonObject();

        ConfigLoadResult result = new ConfigLoader().load(json, Path.of("/cfg"));

        assertTrue(result.hasErrors());
        assertEquals(3, result.diagnostics().stream()
                .filter(diagnostic -> diagnostic.code().equals(ConfigDiagnostics.INVALID_SELECTOR))
                .count());
    }

    @Test
    void rejectsGeneratedClassFallbackMode() {
        JsonObject json = JsonParser.parseString(baseJson().replace("nativeEmbeddedClassBlob", "generatedClass"))
                .getAsJsonObject();

        ConfigLoadResult result = new ConfigLoader().load(json, Path.of("/cfg"));

        assertTrue(result.hasErrors());
        assertEquals(ConfigDiagnostics.UNSUPPORTED_FALLBACK_MODE, result.diagnostics().get(0).code());
    }

    @Test
    void resignRequiresSigningConfig() {
        JsonObject json = JsonParser.parseString(baseJson().replace("\"signaturePolicy\": \"fail\"", "\"signaturePolicy\": \"resign\""))
                .getAsJsonObject();

        ConfigLoadResult result = new ConfigLoader().load(json, Path.of("/cfg"));

        assertTrue(result.hasErrors());
        assertTrue(result.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.message().contains("requires signing config")));
    }

    static String baseJson() {
        return """
                {
                  "schemaVersion": 1,
                  "jarFile": "input.jar",
                  "classPath": [],
                  "javaHome": null,
                  "runtimeImage": null,
                  "worldModel": "PARTIAL_WORLD",
                  "javaSupportTier": "TIER_5",
                  "fallbackMode": "nativeEmbeddedClassBlob",
                  "outputDirectory": "out",
                  "whiteList": [],
                  "blackList": [],
                  "target": {
                    "windowsX64": false,
                    "windowsArm64": false,
                    "linuxX64": true,
                    "linuxArm64": false,
                    "macosX64": false,
                    "macosArm64": false
                  },
                  "libraryName": null,
                  "embeddedLibraryDirectory": "native0",
                  "signaturePolicy": "fail",
                  "signing": null,
                  "intermediates": {
                    "enabled": true,
                    "includeDebugDumps": true,
                    "includePerClassIr": true,
                    "includePerClassLlvm": true,
                    "includePerClassC": true
                  },
                  "protection": {
                    "enabled": true,
                    "seed": null,
                    "intensity": "normal",
                    "ir": {
                      "enabled": true,
                      "controlFlowFlattening": { "enabled": true, "intensity": "normal" },
                      "fakeBranches": { "enabled": true, "intensity": "normal" },
                      "basicBlockSplitting": { "enabled": true, "intensity": "normal" },
                      "constantEncryption": { "enabled": true, "intensity": "normal" },
                      "stringEncryption": { "enabled": true, "intensity": "normal", "cacheStrings": false },
                      "methodInlining": { "enabled": true, "intensity": "normal" },
                      "methodSplitting": { "enabled": true, "intensity": "normal" },
                      "callIndirection": { "enabled": true, "intensity": "normal" },
                      "methodTableHiding": { "enabled": true, "intensity": "normal" }
                    },
                    "llvm": {
                      "enabled": true,
                      "nameObfuscation": { "enabled": true, "intensity": "normal" },
                      "opaquePredicates": { "enabled": true, "intensity": "normal" },
                      "blockLayoutPerturbation": { "enabled": true, "intensity": "normal" },
                      "indirectCalls": { "enabled": true, "intensity": "normal" },
                      "globalLayout": { "enabled": true, "intensity": "normal" },
                      "visibilityHardening": { "enabled": true }
                    },
                    "binary": {
                      "enabled": true,
                      "hideInternalSymbols": true,
                      "strip": true,
                      "removePdb": true,
                      "symbolAudit": true
                    }
                  }
                }
                """;
    }
}
