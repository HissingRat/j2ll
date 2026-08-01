package xyz.melodysky.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import xyz.melodysky.toolchain.TargetTriple;

class ConfigLoaderTest {
    @Test
    void loadsFullSchemaV1AndRandomizesNullableSeed() {
        JsonObject json = JsonParser.parseString(baseJson()).getAsJsonObject();

        ConfigLoadResult result = new ConfigLoader().load(json, Path.of("/cfg"));
        ConfigLoadResult second = new ConfigLoader().load(json, Path.of("/cfg"));

        assertFalse(result.hasErrors());
        ResolvedConfig config = result.config().orElseThrow();
        assertEquals(Path.of("/cfg/input.jar"), config.jarFile());
        assertEquals(TargetTriple.LINUX_X64, config.targets().get(0));
        assertEquals("native0", config.embeddedLibraryDirectory());
        assertEquals(SignaturePolicy.FAIL, config.signaturePolicy());
        assertTrue(config.intermediates().includePerClassLlvm());
        assertTrue(config.protection().ir().constantEncryption());
        assertFalse(config.protection().ir().methodInternalization());
        assertTrue(config.protection().ir().publicMethodInternalizationAllowList().isEmpty());
        assertTrue(config.protection().ir().blockNameObfuscation());
        assertNotNull(config.protection().seed());
        assertEquals(64, config.protection().seed().length());
        assertEquals(ProtectionSeedMode.RANDOMIZED, config.protection().seedMode());
        assertFalse(config.protection().seed().equals(second.config().orElseThrow().protection().seed()));
    }

    @Test
    void explicitSeedSelectsReproducibleMode() {
        JsonObject json = JsonParser.parseString(
                        baseJson().replace("\"seed\": null", "\"seed\": \"release-replay\""))
                .getAsJsonObject();

        ResolvedConfig first = new ConfigLoader().load(json, Path.of("/cfg")).config().orElseThrow();
        ResolvedConfig second = new ConfigLoader().load(json, Path.of("/cfg")).config().orElseThrow();

        assertEquals(ProtectionSeedMode.REPRODUCIBLE, first.protection().seedMode());
        assertEquals("release-replay", first.protection().seed());
        assertEquals(first.protection().seed(), second.protection().seed());
    }

    @Test
    void blockNameObfuscationFlagReachesTheIrProtectionPipeline() {
        JsonObject json = JsonParser.parseString(
                        baseJson().replace("\"blockNameObfuscation\": true", "\"blockNameObfuscation\": false"))
                .getAsJsonObject();

        ConfigLoadResult result = new ConfigLoader().load(json, Path.of("/cfg"));

        assertFalse(result.hasErrors(), result.diagnostics().toString());
        ResolvedConfig config = result.config().orElseThrow();
        assertFalse(config.protection().ir().blockNameObfuscation());
        assertFalse(xyz.melodysky.ir.pass.protection.ProtectionConfig
                .fromResolved(config.protection(), 17)
                .blockNameObfuscation());
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
                        .replace("\"fakeBranches\": true", "\"unknownIrField\": false, \"fakeBranches\": true"))
                .getAsJsonObject();

        ConfigLoadResult result = new ConfigLoader().load(json, Path.of("/cfg"));

        assertFalse(result.hasErrors());
        assertEquals(2, result.diagnostics().stream()
                .filter(diagnostic -> diagnostic.code().equals(ConfigDiagnostics.UNKNOWN_FIELD))
                .count());
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
    void rejectsLegacyIrPassObjectShape() {
        JsonObject json = JsonParser.parseString(baseJson()
                        .replace(
                                "\"controlFlowFlattening\": true",
                                "\"controlFlowFlattening\": { \"enabled\": true }"))
                .getAsJsonObject();

        ConfigLoadResult result = new ConfigLoader().load(json, Path.of("/cfg"));

        assertTrue(result.hasErrors());
        assertTrue(result.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.code().equals(ConfigDiagnostics.INVALID_FIELD_VALUE)
                        && diagnostic.message().contains("protection.ir.controlFlowFlattening")));
    }

    @Test
    void warnsForRemovedVisibilityHardeningField() {
        JsonObject json = JsonParser.parseString(baseJson()
                        .replace(
                                "\"globalLayout\": true",
                                "\"globalLayout\": true, \"visibilityHardening\": true"))
                .getAsJsonObject();

        ConfigLoadResult result = new ConfigLoader().load(json, Path.of("/cfg"));

        assertFalse(result.hasErrors(), result.diagnostics().toString());
        assertTrue(result.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.code().equals(ConfigDiagnostics.UNKNOWN_FIELD)
                        && diagnostic.message().contains("protection.llvm.visibilityHardening")));
    }

    @Test
    void fieldInternalizationDoesNotRequireClosedWorldWhenRootProtectionIsDisabled() {
        JsonObject json = JsonParser.parseString(baseJson()).getAsJsonObject();
        JsonObject protection = json.getAsJsonObject("protection");
        protection.addProperty("enabled", false);
        protection.getAsJsonObject("ir").addProperty("fieldInternalization", true);

        ConfigLoadResult result = new ConfigLoader().load(json, Path.of("/cfg"));

        assertFalse(result.hasErrors(), result.diagnostics().toString());
        assertTrue(result.config().orElseThrow().protection().ir().fieldInternalization());
    }

    @Test
    void fieldInternalizationDoesNotRequireClosedWorldWhenIrProtectionIsDisabled() {
        JsonObject json = JsonParser.parseString(baseJson()).getAsJsonObject();
        JsonObject ir = json.getAsJsonObject("protection").getAsJsonObject("ir");
        ir.addProperty("enabled", false);
        ir.addProperty("fieldInternalization", true);

        ConfigLoadResult result = new ConfigLoader().load(json, Path.of("/cfg"));

        assertFalse(result.hasErrors(), result.diagnostics().toString());
        assertTrue(result.config().orElseThrow().protection().ir().fieldInternalization());
    }

    @Test
    void enabledFieldInternalizationRequiresBuildConfirmationOutsideClosedWorld() {
        JsonObject json = JsonParser.parseString(baseJson()).getAsJsonObject();
        json.getAsJsonObject("protection")
                .getAsJsonObject("ir")
                .addProperty("fieldInternalization", true);

        ConfigLoadResult result = new ConfigLoader().load(json, Path.of("/cfg"));

        assertFalse(result.hasErrors(), result.diagnostics().toString());
        assertTrue(result.config().isPresent());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code().equals(ConfigDiagnostics.FIELD_INTERNALIZATION_REQUIRES_CLOSED_WORLD)
                        && diagnostic.severity() == xyz.melodysky.diagnostic.DiagnosticSeverity.WARNING
                        && "confirmationRequired".equals(diagnostic.decision())));
    }

    @Test
    void enabledFieldInternalizationNeedsNoConfirmationInClosedWorld() {
        JsonObject json = JsonParser.parseString(baseJson()).getAsJsonObject();
        json.addProperty("worldModel", "CLOSED_WORLD");
        json.getAsJsonObject("protection")
                .getAsJsonObject("ir")
                .addProperty("fieldInternalization", true);

        ConfigLoadResult result = new ConfigLoader().load(json, Path.of("/cfg"));

        assertFalse(result.hasErrors(), result.diagnostics().toString());
        assertTrue(result.diagnostics().stream().noneMatch(diagnostic ->
                diagnostic.code().equals(ConfigDiagnostics.FIELD_INTERNALIZATION_REQUIRES_CLOSED_WORLD)));
    }

    @Test
    void enabledMethodInternalizationRequiresBuildConfirmationOutsideClosedWorld() {
        JsonObject json = JsonParser.parseString(baseJson()).getAsJsonObject();
        json.getAsJsonObject("protection")
                .getAsJsonObject("ir")
                .addProperty("methodInternalization", true);

        ConfigLoadResult result = new ConfigLoader().load(json, Path.of("/cfg"));

        assertFalse(result.hasErrors(), result.diagnostics().toString());
        assertTrue(result.config().orElseThrow().protection().ir().methodInternalization());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code().equals(ConfigDiagnostics.METHOD_INTERNALIZATION_REQUIRES_CLOSED_WORLD)
                        && diagnostic.severity() == xyz.melodysky.diagnostic.DiagnosticSeverity.WARNING
                        && "confirmationRequired".equals(diagnostic.decision())));
    }

    @Test
    void enabledMethodInternalizationNeedsNoConfirmationInClosedWorld() {
        JsonObject json = JsonParser.parseString(baseJson()).getAsJsonObject();
        json.addProperty("worldModel", "CLOSED_WORLD");
        json.getAsJsonObject("protection")
                .getAsJsonObject("ir")
                .addProperty("methodInternalization", true);

        ConfigLoadResult result = new ConfigLoader().load(json, Path.of("/cfg"));

        assertFalse(result.hasErrors(), result.diagnostics().toString());
        assertTrue(result.diagnostics().stream().noneMatch(diagnostic ->
                diagnostic.code().equals(ConfigDiagnostics.METHOD_INTERNALIZATION_REQUIRES_CLOSED_WORLD)));
    }

    @Test
    void rejectsConfigMissingMethodInternalization() {
        JsonObject json = JsonParser.parseString(baseJson()).getAsJsonObject();
        json.getAsJsonObject("protection")
                .getAsJsonObject("ir")
                .remove("methodInternalization");

        ConfigLoadResult result = new ConfigLoader().load(json, Path.of("/cfg"));

        assertTrue(result.hasErrors());
        assertTrue(result.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.code().equals(ConfigDiagnostics.MISSING_REQUIRED_FIELD)
                        && diagnostic.message().contains("protection.ir.methodInternalization")));
    }

    @Test
    void acceptsExactPublicMethodInternalizationSelectorsInDeclaredOrder() {
        JsonObject json = JsonParser.parseString(baseJson()).getAsJsonObject();
        JsonArray allowList = new JsonArray();
        allowList.add("fixture/PublicApi#zeta!(Ljava/lang/String;)V");
        allowList.add("fixture/PublicApi#alpha!()I");
        json.getAsJsonObject("protection")
                .getAsJsonObject("ir")
                .add("publicMethodInternalizationAllowList", allowList);

        ConfigLoadResult result = new ConfigLoader().load(json, Path.of("/cfg"));

        assertFalse(result.hasErrors(), result.diagnostics().toString());
        assertEquals(
                java.util.List.of(
                        "fixture/PublicApi#zeta!(Ljava/lang/String;)V",
                        "fixture/PublicApi#alpha!()I"),
                result.config().orElseThrow().protection().ir()
                        .publicMethodInternalizationAllowList().stream()
                        .map(Selector::raw)
                        .toList());
    }

    @Test
    void rejectsClassWildcardAndDuplicatePublicMethodAuthorization() {
        for (java.util.List<String> invalid : java.util.List.of(
                java.util.List.of("fixture/PublicApi"),
                java.util.List.of("fixture/*#run!()V"),
                java.util.List.of(
                        "fixture/PublicApi#run!()V",
                        "fixture/PublicApi#run!()V"))) {
            JsonObject json = JsonParser.parseString(baseJson()).getAsJsonObject();
            JsonArray allowList = new JsonArray();
            invalid.forEach(allowList::add);
            json.getAsJsonObject("protection")
                    .getAsJsonObject("ir")
                    .add("publicMethodInternalizationAllowList", allowList);

            ConfigLoadResult result = new ConfigLoader().load(json, Path.of("/cfg"));

            assertTrue(result.hasErrors(), invalid.toString());
            assertTrue(result.diagnostics().stream()
                    .anyMatch(diagnostic -> diagnostic.code().equals(ConfigDiagnostics.INVALID_SELECTOR)
                            && diagnostic.message().contains("publicMethodInternalizationAllowList")),
                    result.diagnostics().toString());
        }
    }

    @Test
    void rejectsMissingNonArrayOrNonStringPublicMethodAuthorizationList() {
        JsonObject missing = JsonParser.parseString(baseJson()).getAsJsonObject();
        missing.getAsJsonObject("protection")
                .getAsJsonObject("ir")
                .remove("publicMethodInternalizationAllowList");
        ConfigLoadResult missingResult = new ConfigLoader().load(missing, Path.of("/cfg"));
        assertTrue(missingResult.hasErrors());
        assertTrue(missingResult.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.code().equals(ConfigDiagnostics.MISSING_REQUIRED_FIELD)
                        && diagnostic.message().contains("publicMethodInternalizationAllowList")));

        JsonObject nonArray = JsonParser.parseString(baseJson()).getAsJsonObject();
        nonArray.getAsJsonObject("protection")
                .getAsJsonObject("ir")
                .addProperty("publicMethodInternalizationAllowList", true);
        ConfigLoadResult nonArrayResult = new ConfigLoader().load(nonArray, Path.of("/cfg"));
        assertTrue(nonArrayResult.hasErrors());
        assertTrue(nonArrayResult.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.code().equals(ConfigDiagnostics.INVALID_FIELD_VALUE)
                        && diagnostic.message().contains("publicMethodInternalizationAllowList")));

        JsonObject invalidEntry = JsonParser.parseString(baseJson()).getAsJsonObject();
        JsonArray allowList = new JsonArray();
        allowList.add(7);
        invalidEntry.getAsJsonObject("protection")
                .getAsJsonObject("ir")
                .add("publicMethodInternalizationAllowList", allowList);
        ConfigLoadResult invalidResult = new ConfigLoader().load(invalidEntry, Path.of("/cfg"));
        assertTrue(invalidResult.hasErrors());
        assertTrue(invalidResult.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.code().equals(ConfigDiagnostics.INVALID_FIELD_VALUE)
                        && diagnostic.message().contains("publicMethodInternalizationAllowList[0]")));
    }

    @Test
    void rejectsMissingBlockNameObfuscation() {
        JsonObject json = JsonParser.parseString(baseJson()
                        .replace("\"blockNameObfuscation\": true", "\"legacyBlockName\": true"))
                .getAsJsonObject();

        ConfigLoadResult result = new ConfigLoader().load(json, Path.of("/cfg"));

        assertTrue(result.hasErrors());
        assertTrue(result.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.code().equals(ConfigDiagnostics.MISSING_REQUIRED_FIELD)
                        && diagnostic.message().contains("protection.ir.blockNameObfuscation")));
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
    void acceptsCanonicalNestedEmbeddedLibraryDirectory() {
        JsonObject json = JsonParser.parseString(baseJson()
                        .replace(
                                "\"embeddedLibraryDirectory\": \"native0\"",
                                "\"embeddedLibraryDirectory\": \"xyz/Melody/natives\""))
                .getAsJsonObject();

        ConfigLoadResult result = new ConfigLoader().load(json, Path.of("/cfg"));

        assertFalse(result.hasErrors(), result.diagnostics().toString());
        assertEquals(
                "xyz/Melody/natives",
                result.config().orElseThrow().embeddedLibraryDirectory());
    }

    @Test
    void rejectsEmbeddedLibraryDirectoryThatIsNotAJavaPackagePath() {
        for (String invalid : java.util.List.of(
                "foo.bar",
                "../native",
                "native0/",
                "native0//nested",
                "java/lang",
                "META-INF")) {
            JsonObject json = JsonParser.parseString(baseJson()
                            .replace(
                                    "\"embeddedLibraryDirectory\": \"native0\"",
                                    "\"embeddedLibraryDirectory\": \"" + invalid + "\""))
                    .getAsJsonObject();

            ConfigLoadResult result = new ConfigLoader().load(json, Path.of("/cfg"));

            assertTrue(result.hasErrors(), invalid);
            assertTrue(result.diagnostics().stream()
                    .anyMatch(diagnostic -> diagnostic.code()
                            .equals(ConfigDiagnostics.INVALID_EMBEDDED_LIBRARY_DIRECTORY)), invalid);
        }
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
                    "ir": {
                      "enabled": true,
                      "controlFlowFlattening": true,
                      "fakeBranches": true,
                      "basicBlockSplitting": true,
                      "constantEncryption": true,
                      "stringEncryption": true,
                      "methodInlining": true,
                      "methodSplitting": true,
                      "callIndirection": true,
                      "fieldInternalization": false,
                      "methodInternalization": false,
                      "publicMethodInternalizationAllowList": [],
                      "methodTableHiding": true,
                      "blockNameObfuscation": true
                    },
                    "llvm": {
                      "enabled": true,
                      "nameObfuscation": true,
                      "opaquePredicates": true,
                      "blockLayoutPerturbation": true,
                      "indirectCalls": true,
                      "globalLayout": true
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
