package xyz.melodysky.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import xyz.melodysky.config.SignaturePolicy;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassFileEntry;
import xyz.melodysky.frontend.classfile.ParsedClass;
import xyz.melodysky.packaging.MethodRewriteDecision;
import xyz.melodysky.packaging.MethodRewritePlanner;
import xyz.melodysky.packaging.MethodTableHidingPlan;
import xyz.melodysky.packaging.MethodTableHidingPlanner;
import xyz.melodysky.packaging.NativeRegistrationEntry;
import xyz.melodysky.packaging.NativeRegistrationPlan;
import xyz.melodysky.packaging.SignatureActionReport;
import xyz.melodysky.toolchain.HostPlatform;
import xyz.melodysky.toolchain.ManagedZig;
import xyz.melodysky.toolchain.NativeBuildPlan;
import xyz.melodysky.toolchain.NativeBuildPlanner;
import xyz.melodysky.toolchain.NativeLibraryArtifact;
import xyz.melodysky.toolchain.TargetTriple;
import xyz.melodysky.toolchain.ZigBuildInvocation;
import xyz.melodysky.toolchain.ZigNativeBuildResult;
import xyz.melodysky.testsupport.AsmFixtureBuilder;

class PackagingReportWriterTest {
    @Test
    void writesPackagingReportMinimumSchemaGolden() {
        ParsedClass parsedClass = new AsmClassParser()
                .parse(new ClassFileEntry("pkg/Mathy.class", AsmFixtureBuilder.classWithAddMethod("pkg/Mathy"), "fixture"))
                .artifact()
                .orElseThrow();
        MethodRewriteDecision decision = new MethodRewritePlanner().planClass(parsedClass).stream()
                .filter(item -> item.method().name().equals("add"))
                .findFirst()
                .orElseThrow();

        assertEquals("""
                {
                  "schemaVersion": 1,
                  "reportVersion": 1,
                  "outputJar": "input.jar",
                  "manifestPolicy": "preserved",
                  "signaturePolicy": "fail",
                  "preservationSummary": {
                    "manifestPreserved": false,
                    "serviceEntriesPreserved": 0,
                    "moduleInfoPreserved": false,
                    "multiRelease": false,
                    "versionedEntriesPreserved": 0,
                    "versionedClassPolicy": "baseClassesOnly"
                  },
                  "signatureAction": {
                    "action": "none",
                    "signedInput": false,
                    "removedEntries": [],
                    "reasonCode": "SIGNATURE_NOT_PRESENT",
                    "reason": "input JAR is not signed"
                  },
                  "generatedLoaders": [
                    "native0/Loader"
                  ],
                  "rewrittenClasses": [
                    {
                      "class": "pkg/Mathy",
                      "methods": [
                        {
                          "method": "add",
                          "descriptor": "(II)I",
                          "rewriteStrategy": "nativeOriginal",
                          "registrationOwner": "pkg/Mathy"
                        }
                      ]
                    }
                  ],
                  "embeddedLibraries": [
                    {
                      "target": "linux-x64",
                      "jarPath": "native0/x64-linux.so",
                      "sha256": "012345"
                    }
                  ],
                  "zigToolchain": {
                    "managed": true,
                    "version": null,
                    "executable": null,
                    "buildZig": null,
                    "verificationPolicy": null,
                    "selectedTargets": [],
                    "requiredTargets": [],
                    "buildableTargets": [],
                    "skippedTargets": [],
                    "failedTargets": [],
                    "targetArtifacts": [],
                    "bootstrapEvents": []
                  },
                  "registeredNativeMethods": [
                    {
                      "registrationOwner": "pkg/Mathy",
                      "method": "add",
                      "descriptor": "(II)I",
                      "nativeSymbol": "j2ll_pkg_Mathy_add_0123"
                    }
                  ],
                  "registrationGroups": [
                    {
                      "registrationOwner": "pkg/Mathy",
                      "methods": [
                        {
                          "method": "add",
                          "descriptor": "(II)I",
                          "nativeSymbol": "j2ll_pkg_Mathy_add_0123"
                        }
                      ]
                    }
                  ],
                  "methodTableHiding": {
                    "enabled": false,
                    "status": "SKIPPED",
                    "planId": null,
                    "physicalStrategy": "ownerLocalTransientStraightLine",
                    "runtimeTokenTableEmitted": false,
                    "runtimeFunctionTableEmitted": false,
                    "temporaryJniTableZeroized": true,
                    "bindingTokensAreReportEvidenceOnly": true,
                    "ownerCount": 0,
                    "bindingCount": 0,
                    "owners": []
                  },
                  "exportedSymbols": [
                    "JNI_OnLoad"
                  ]
                }
                """, new PackagingReportWriter().packagingJson(
                Path.of("input.jar"),
                SignaturePolicy.FAIL,
                List.of("native0/Loader"),
                List.of(decision),
                List.of(new EmbeddedLibraryReport("linux-x64", "native0/x64-linux.so", "012345")),
                List.of(new NativeRegistrationEntry("pkg/Mathy", "add", "(II)I", "j2ll_pkg_Mathy_add_0123")),
                List.of("JNI_OnLoad"),
                null));
    }

    @Test
    void omitsRemovedFallbackBlobSurface() {
        String json = new PackagingReportWriter().packagingJson(
                Path.of("input.jar"),
                SignaturePolicy.FAIL,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null);

        assertFalse(json.contains("fallbackBlobs"), json);
        assertFalse(json.contains("nativeEmbeddedClassBlob"), json);
    }

    @Test
    void writesTargetPackagePlanEvidenceForAllPlatformFamilies() {
        NativeBuildPlan plan = new NativeBuildPlanner(Optional.of(new HostPlatform(TargetTriple.MACOS_ARM64, "darwin"))).plan(
                Path.of("workspace"),
                "j2llapp",
                List.of(TargetTriple.WINDOWS_X64, TargetTriple.LINUX_X64, TargetTriple.MACOS_ARM64));

        String json = new PackagingReportWriter().packagingJson(
                Path.of("input.jar"),
                SignaturePolicy.FAIL,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                plan);

        assertTrue(json.contains("\"target\": \"linux-x64\""), json);
        assertTrue(json.contains("\"expectedArtifactPath\": \"workspace/native/x64-linux.so\""), json);
        assertTrue(json.contains("\"expectedResourcePath\": \"native/x64-linux.so\""), json);
        assertTrue(json.contains("\"target\": \"macos-arm64\""), json);
        assertTrue(json.contains("\"expectedArtifactPath\": \"workspace/native/arm64-macos.dylib\""), json);
        assertTrue(json.contains("\"expectedResourcePath\": \"native/arm64-macos.dylib\""), json);
        assertTrue(json.contains("\"target\": \"windows-x64\""), json);
        assertTrue(json.contains("\"expectedArtifactPath\": \"workspace/native/x64-windows.dll\""), json);
        assertTrue(json.contains("\"expectedResourcePath\": \"native/x64-windows.dll\""), json);
        assertTrue(json.contains("\"windowsPdbPolicy\": \"excludePdbFromJarAndReports\""), json);
        assertEquals(List.of("linux-x64", "macos-arm64", "windows-x64"),
                JsonParser.parseString(json)
                        .getAsJsonObject()
                        .getAsJsonObject("zigToolchain")
                        .getAsJsonArray("targetArtifacts")
                        .asList()
                        .stream()
                        .map(element -> element.getAsJsonObject().get("target").getAsString())
                        .toList());
    }

    @Test
    void writesSelectedTargetPreflightSummary() {
        NativeBuildPlan plan = new NativeBuildPlanner(Optional.of(new HostPlatform(TargetTriple.MACOS_ARM64, "darwin"))).plan(
                Path.of("/work"),
                "j2llapp",
                List.of(TargetTriple.LINUX_X64, TargetTriple.MACOS_ARM64));

        String json = new PackagingReportWriter().packagingJson(
                Path.of("input.jar"),
                SignaturePolicy.FAIL,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                plan);
        var zig = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("zigToolchain");
        assertEquals(List.of("linux-x64", "macos-arm64"), zig.getAsJsonArray("buildableTargets").asList().stream()
                .map(element -> element.getAsString())
                .toList());
        assertTrue(zig.getAsJsonArray("failedTargets").isEmpty(), json);
        var targets = zig.getAsJsonArray("targetArtifacts").asList().stream()
                .map(element -> element.getAsJsonObject())
                .toList();
        assertEquals("x86_64-linux.3.2-gnu.2.17", targets.get(0).get("zigTarget").getAsString());
        assertEquals("ZIG_CROSS_TARGET_SUPPORTED", targets.get(0).get("reasonCode").getAsString());
        assertEquals("aarch64-macos.11.0", targets.get(1).get("zigTarget").getAsString());
        assertEquals("CURRENT_HOST_TARGET", targets.get(1).get("reasonCode").getAsString());
        assertTrue(targets.stream().allMatch(target -> target.get("buildable").getAsBoolean()));
        assertTrue(targets.stream().allMatch(target -> "none".equals(target.get("failureKind").getAsString())));
    }

    @Test
    void writesReleaseTargetArtifactsWithActualHashAndPdbPolicy() {
        NativeBuildPlan plan = new NativeBuildPlanner(Optional.of(new HostPlatform(TargetTriple.WINDOWS_X64, "windows"))).plan(
                Path.of("/work"),
                "j2llapp",
                List.of(TargetTriple.WINDOWS_X64));
        ZigNativeBuildResult result = new ZigNativeBuildResult(
                new ManagedZig(Path.of("/j2ll/zig/zig.exe"), Path.of("/j2ll/zig"), "0.15.2", "checksumInterfacePending"),
                Path.of("/work/native/zig-workspace/build.zig"),
                Path.of("/work/native/zig-workspace/j2ll-build-manifest.json"),
                Path.of("/work/native/zig-workspace/c/jni_wrappers.c"),
                List.of(new NativeLibraryArtifact(
                        TargetTriple.WINDOWS_X64,
                        Path.of("/work/native/x64-windows.dll"),
                        Path.of("/work/native/zig-workspace/c/jni_wrappers.c"),
                        "native/x64-windows.dll",
                        "a".repeat(64),
                        List.of("JNI_OnLoad"))),
                new ZigBuildInvocation(
                        Path.of("/j2ll/zig/zig.exe"),
                        Path.of("/work/native/zig-workspace"),
                        List.of("/j2ll/zig/zig.exe", "build"),
                        Path.of("/work/logs/zig-build.log")));

        String json = new PackagingReportWriter().packagingJson(
                Path.of("input.jar"),
                SignaturePolicy.FAIL,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                result,
                plan);

        assertTrue(json.contains("\"targetArtifacts\""));
        assertTrue(json.contains("\"osClassifier\": \"windows\""));
        assertTrue(json.contains("\"archClassifier\": \"x64\""));
        assertTrue(json.contains("\"libraryExtension\": \"dll\""));
        assertTrue(json.contains("\"expectedArtifactPath\": \"/work/native/x64-windows.dll\""));
        assertTrue(json.contains("\"expectedResourcePath\": \"native/x64-windows.dll\""));
        assertTrue(json.contains("\"loaderExtractionPathPolicy\": \"contentAddressedTempCacheBySha256\""));
        assertTrue(json.contains("\"symbolVisibilityPolicy\": \"allowlistOnlyJniOnLoadAndBootstrap\""));
        assertTrue(json.contains("\"actualSha256\": \"" + "a".repeat(64) + "\""));
        assertTrue(json.contains("\"actualArtifactPath\": \"/work/native/x64-windows.dll\""));
        assertTrue(json.contains("\"actualJarPath\": \"native/x64-windows.dll\""));
        assertTrue(json.contains("\"windowsPdbPolicy\": \"excludePdbFromJarAndReports\""));
        assertTrue(json.contains("\"exportedSymbols\": [\n          \"JNI_OnLoad\"\n        ]"));
        assertTrue(json.contains("\"bootstrapEvents\""));
    }

    @Test
    void writesSuccessfulResignAction() {
        String json = new PackagingReportWriter().packagingJson(
                Path.of("input.jar"),
                SignaturePolicy.RESIGN,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                new NativeBuildPlan(List.of()),
                xyz.melodysky.packaging.JarPreservationReport.empty(),
                SignatureActionReport.resigned(true, List.of("META-INF/OLD.SF", "META-INF/OLD.RSA")));

        assertTrue(json.contains("\"signaturePolicy\": \"resign\""));
        assertTrue(json.contains("\"action\": \"resign\""));
        assertTrue(json.contains("\"reasonCode\": \"SIGNATURE_RESIGNED\""));
        assertTrue(json.contains("\"META-INF/OLD.SF\""));
    }

    @Test
    void writesStableHashOnlyMethodTableHidingEvidence() {
        List<NativeRegistrationEntry> registrations = List.of(
                new NativeRegistrationEntry(
                        "secret/pkg/Beta",
                        "hiddenBeta",
                        "(Ljava/lang/String;)V",
                        "j2ll_secret_beta"),
                new NativeRegistrationEntry(
                        "secret/pkg/Alpha",
                        "hiddenAdd",
                        "(II)I",
                        "j2ll_secret_add"),
                new NativeRegistrationEntry(
                        "secret/pkg/Alpha",
                        "hiddenPing",
                        "()V",
                        "j2ll_secret_ping"));
        MethodTableHidingPlan hidingPlan = new MethodTableHidingPlanner().plan(
                new NativeRegistrationPlan(registrations),
                true,
                0x4d54485f5245504fL);

        String json = new PackagingReportWriter().packagingJson(
                Path.of("input.jar"),
                SignaturePolicy.FAIL,
                List.of(),
                List.of(),
                List.of(),
                registrations,
                hidingPlan,
                List.of(),
                null,
                new NativeBuildPlan(List.of()),
                xyz.melodysky.packaging.JarPreservationReport.empty(),
                SignatureActionReport.none(false));

        var evidence = JsonParser.parseString(json)
                .getAsJsonObject()
                .getAsJsonObject("methodTableHiding");
        assertTrue(evidence.get("enabled").getAsBoolean());
        assertEquals("RAN", evidence.get("status").getAsString());
        assertEquals(hidingPlan.planId(), evidence.get("planId").getAsString());
        assertTrue(evidence.get("planId").getAsString().matches("mth_[0-9a-f]{32}"));
        assertEquals(
                "ownerLocalTransientStraightLine",
                evidence.get("physicalStrategy").getAsString());
        assertFalse(evidence.get("runtimeTokenTableEmitted").getAsBoolean());
        assertFalse(evidence.get("runtimeFunctionTableEmitted").getAsBoolean());
        assertTrue(evidence.get("temporaryJniTableZeroized").getAsBoolean());
        assertTrue(evidence
                .get("bindingTokensAreReportEvidenceOnly")
                .getAsBoolean());
        assertEquals(2, evidence.get("ownerCount").getAsInt());
        assertEquals(3, evidence.get("bindingCount").getAsInt());

        var owners = evidence.getAsJsonArray("owners").asList().stream()
                .map(element -> element.getAsJsonObject())
                .toList();
        assertEquals(2, owners.size());
        List<String> ownerHashes = owners.stream()
                .map(owner -> owner.get("ownerHash").getAsString())
                .toList();
        assertEquals(ownerHashes.stream().sorted().toList(), ownerHashes);
        assertTrue(ownerHashes.stream().allMatch(hash -> hash.matches("[0-9a-f]{64}")));
        assertEquals(3, owners.stream()
                .mapToInt(owner -> owner.get("bindingCount").getAsInt())
                .sum());
        owners.forEach(owner -> {
            List<String> tokens = owner.getAsJsonArray("bindingTokens").asList().stream()
                    .map(element -> element.getAsString())
                    .toList();
            assertEquals(tokens.stream().sorted().toList(), tokens);
            assertTrue(tokens.stream().allMatch(token -> token.matches("[0-9a-f]{16}")));
        });

        String evidenceJson = evidence.toString();
        assertFalse(evidenceJson.contains("secret/pkg/Alpha"));
        assertFalse(evidenceJson.contains("hiddenAdd"));
        assertFalse(evidenceJson.contains("(II)I"));
        assertFalse(evidenceJson.contains("j2ll_secret_add"));
    }
}
