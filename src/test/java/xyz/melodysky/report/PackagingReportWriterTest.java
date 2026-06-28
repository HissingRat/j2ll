package xyz.melodysky.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import xyz.melodysky.packaging.NativeEmbeddedFallbackBlob;
import xyz.melodysky.packaging.NativeRegistrationEntry;
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
                  "outputJar": "output/input.jar",
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
                    "j2ll/generated/abc123/NativeLoader"
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
                  "exportedSymbols": [
                    "JNI_OnLoad",
                    "j2ll_register"
                  ],
                  "fallbackBlobs": []
                }
                """, new PackagingReportWriter().packagingJson(
                Path.of("output/input.jar"),
                SignaturePolicy.FAIL,
                List.of("j2ll/generated/abc123/NativeLoader"),
                List.of(decision),
                List.of(new EmbeddedLibraryReport("linux-x64", "native0/x64-linux.so", "012345")),
                List.of(new NativeRegistrationEntry("pkg/Mathy", "add", "(II)I", "j2ll_pkg_Mathy_add_0123")),
                List.of("j2ll_register", "JNI_OnLoad"),
                null,
                List.of()));
    }

    @Test
    void writesNativeEmbeddedFallbackBlobManifestMetadata() {
        NativeEmbeddedFallbackBlob blob = new NativeEmbeddedFallbackBlob(
                "run__8f3a21c0d4e5f607",
                "pkg/Foo#run!()V",
                "pkg/J2llFallback$run__8f3a21c0d4e5f607",
                "()V",
                "METHOD_HANDLE_CHAIN_FALLBACK",
                "0".repeat(64),
                "1".repeat(64),
                "0".repeat(64),
                "fallbackBlobEncodingV1",
                123,
                234,
                "j2ll-rle-byte-pairs-v1",
                "xor-sha256-key-stream-v1",
                "8",
                "nativeEmbeddedClassBlob",
                "HiddenClass",
                "FALLBACK_HIDDEN_CLASS",
                true,
                true,
                "owner-private Lookup can define hidden fallback helper class",
                "FALLBACK_CACHE_REUSE",
                "lazyPerClassLoaderReuse",
                "process",
                "fallbackId+definingClassLoaderIdentity",
                "processLifetime",
                "globalRefPerFallbackClassAndClassLoader");

        assertEquals("""
                {
                  "schemaVersion": 1,
                  "reportVersion": 1,
                  "outputJar": "output/input.jar",
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
                  "generatedLoaders": [],
                  "rewrittenClasses": [],
                  "embeddedLibraries": [],
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
                  "registeredNativeMethods": [],
                  "registrationGroups": [],
                  "exportedSymbols": [],
                  "fallbackBlobs": [
                    {
                      "originalMethodId": "run__8f3a21c0d4e5f607",
                      "originalMethodKey": "pkg/Foo#run!()V",
                      "helperClassName": "pkg/J2llFallback$run__8f3a21c0d4e5f607",
                      "fallbackInvokeDescriptor": "()V",
                      "fallbackReasonCode": "METHOD_HANDLE_CHAIN_FALLBACK",
                      "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
                      "originalSha256": "1111111111111111111111111111111111111111111111111111111111111111",
                      "encodedSha256": "0000000000000000000000000000000000000000000000000000000000000000",
                      "encodingVersion": "fallbackBlobEncodingV1",
                      "originalSize": 123,
                      "encodedSize": 234,
                      "compressionAlgorithm": "j2ll-rle-byte-pairs-v1",
                      "encryptionAlgorithm": "xor-sha256-key-stream-v1",
                      "requiredJavaVersion": "8",
                      "storageTarget": "nativeEmbeddedClassBlob",
                      "definitionMechanism": "HiddenClass",
                      "definitionMechanismReasonCode": "FALLBACK_HIDDEN_CLASS",
                      "hiddenClassApiAvailable": true,
                      "ownerLookupSupported": true,
                      "definitionMechanismReason": "owner-private Lookup can define hidden fallback helper class",
                      "cacheReasonCode": "FALLBACK_CACHE_REUSE",
                      "classloaderReusePolicy": "lazyPerClassLoaderReuse",
                      "cacheScope": "process",
                      "cacheKey": "fallbackId+definingClassLoaderIdentity",
                      "cacheLifetime": "processLifetime",
                      "globalReferencePolicy": "globalRefPerFallbackClassAndClassLoader",
                      "unloadAware": false,
                      "futurePath": "replace process-lifetime global-ref cache with unload-aware weak/global-reference lifecycle discipline"
                    }
                  ]
                }
                """, new PackagingReportWriter().packagingJson(
                Path.of("output/input.jar"),
                SignaturePolicy.FAIL,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of(blob)));
    }

    @Test
    void writesTargetPackagePlanEvidenceForAllPlatformFamilies() {
        NativeBuildPlan plan = new NativeBuildPlanner(Optional.of(new HostPlatform(TargetTriple.MACOS_ARM64, "darwin"))).plan(
                Path.of("workspace"),
                "j2llapp",
                List.of(TargetTriple.WINDOWS_X64, TargetTriple.LINUX_X64, TargetTriple.MACOS_ARM64));

        String json = new PackagingReportWriter().packagingJson(
                Path.of("output/input.jar"),
                SignaturePolicy.FAIL,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                plan,
                List.of());

        assertTrue(json.contains("\"target\": \"linux-x64\""), json);
        assertTrue(json.contains("\"expectedResourcePath\": \"native/linux-x64/x64-linux.so\""), json);
        assertTrue(json.contains("\"target\": \"macos-arm64\""), json);
        assertTrue(json.contains("\"expectedResourcePath\": \"native/macos-arm64/arm64-macos.dylib\""), json);
        assertTrue(json.contains("\"target\": \"windows-x64\""), json);
        assertTrue(json.contains("\"expectedResourcePath\": \"native/windows-x64/x64-windows.dll\""), json);
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

        assertEquals("""
                {
                  "schemaVersion": 1,
                  "reportVersion": 1,
                  "outputJar": "output/input.jar",
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
                  "generatedLoaders": [],
                  "rewrittenClasses": [],
                  "embeddedLibraries": [],
                  "zigToolchain": {
                    "managed": true,
                    "version": null,
                    "executable": null,
                    "buildZig": null,
                    "verificationPolicy": null,
                    "selectedTargets": [
                      "linux-x64",
                      "macos-arm64"
                    ],
                    "requiredTargets": [
                      "linux-x64",
                      "macos-arm64"
                    ],
                    "buildableTargets": [
                      "macos-arm64"
                    ],
                    "skippedTargets": [],
                    "failedTargets": [
                      {
                        "target": "linux-x64",
                        "zigTarget": "x86_64-linux",
                        "output": "/work/native/linux-x64/x64-linux.so",
                        "status": "failed",
                        "currentHost": false,
                        "required": true,
                        "buildable": false,
                        "reasonCode": "ZIG_TARGET_UNBUILDABLE",
                        "reason": "selected required target linux-x64 is not buildable by the current managed Zig workspace preflight",
                        "requiredCapability": "managedZig0.15.2BuildZigSharedLibrary",
                        "platformSdkRequirement": "Zig Linux libc/linker support for selected target",
                        "failureKind": "unsupportedLibc",
                        "buildLogTail": "preflight only: no Zig build invoked for required unbuildable target linux-x64"
                      }
                    ],
                    "targetArtifacts": [
                      {
                        "target": "linux-x64",
                        "required": true,
                        "currentHost": false,
                        "buildable": false,
                        "osClassifier": "linux",
                        "archClassifier": "x64",
                        "libraryExtension": "so",
                        "libraryName": "j2llapp",
                        "zigTarget": "x86_64-linux",
                        "expectedArtifactPath": "/work/native/linux-x64/x64-linux.so",
                        "expectedArtifactName": "x64-linux.so",
                        "expectedResourcePath": "native/linux-x64/x64-linux.so",
                        "loaderExtractionPathPolicy": "contentAddressedTempCacheBySha256",
                        "symbolVisibilityPolicy": "allowlistOnlyJniOnLoadAndBootstrap",
                        "windowsPdbPolicy": "notApplicable",
                        "actualArtifactPath": null,
                        "actualJarPath": null,
                        "actualSha256": null,
                        "exportedSymbols": [],
                        "status": "failed",
                        "reasonCode": "ZIG_TARGET_UNBUILDABLE",
                        "reason": "selected required target linux-x64 is not buildable by the current managed Zig workspace preflight",
                        "requiredCapability": "managedZig0.15.2BuildZigSharedLibrary",
                        "platformSdkRequirement": "Zig Linux libc/linker support for selected target",
                        "failureKind": "unsupportedLibc",
                        "buildLogTail": "preflight only: no Zig build invoked for required unbuildable target linux-x64"
                      },
                      {
                        "target": "macos-arm64",
                        "required": true,
                        "currentHost": true,
                        "buildable": true,
                        "osClassifier": "macos",
                        "archClassifier": "arm64",
                        "libraryExtension": "dylib",
                        "libraryName": "j2llapp",
                        "zigTarget": "aarch64-macos",
                        "expectedArtifactPath": "/work/native/macos-arm64/arm64-macos.dylib",
                        "expectedArtifactName": "arm64-macos.dylib",
                        "expectedResourcePath": "native/macos-arm64/arm64-macos.dylib",
                        "loaderExtractionPathPolicy": "contentAddressedTempCacheBySha256",
                        "symbolVisibilityPolicy": "allowlistOnlyJniOnLoadAndBootstrap",
                        "windowsPdbPolicy": "notApplicable",
                        "actualArtifactPath": null,
                        "actualJarPath": null,
                        "actualSha256": null,
                        "exportedSymbols": [],
                        "status": "buildable",
                        "reasonCode": "CURRENT_HOST_TARGET",
                        "reason": "selected target matches the current JVM host and is buildable now",
                        "requiredCapability": "managedZig0.15.2BuildZigSharedLibrary",
                        "platformSdkRequirement": "macOS SDK and linker support for selected target",
                        "failureKind": "none",
                        "buildLogTail": "preflight buildable; Zig build log is recorded after invocation"
                      }
                    ],
                    "bootstrapEvents": []
                  },
                  "registeredNativeMethods": [],
                  "registrationGroups": [],
                  "exportedSymbols": [],
                  "fallbackBlobs": []
                }
                """, new PackagingReportWriter().packagingJson(
                Path.of("output/input.jar"),
                SignaturePolicy.FAIL,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                plan,
                List.of()));
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
                        Path.of("/work/native/windows-x64/x64-windows.dll"),
                        Path.of("/work/native/zig-workspace/c/jni_wrappers.c"),
                        "native/windows-x64/x64-windows.dll",
                        "a".repeat(64),
                        List.of("JNI_OnLoad"))),
                new ZigBuildInvocation(
                        Path.of("/j2ll/zig/zig.exe"),
                        Path.of("/work/native/zig-workspace"),
                        List.of("/j2ll/zig/zig.exe", "build"),
                        Path.of("/work/logs/zig-build.log")));

        String json = new PackagingReportWriter().packagingJson(
                Path.of("output/input.jar"),
                SignaturePolicy.FAIL,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                result,
                plan,
                List.of());

        assertTrue(json.contains("\"targetArtifacts\""));
        assertTrue(json.contains("\"osClassifier\": \"windows\""));
        assertTrue(json.contains("\"archClassifier\": \"x64\""));
        assertTrue(json.contains("\"libraryExtension\": \"dll\""));
        assertTrue(json.contains("\"expectedResourcePath\": \"native/windows-x64/x64-windows.dll\""));
        assertTrue(json.contains("\"loaderExtractionPathPolicy\": \"contentAddressedTempCacheBySha256\""));
        assertTrue(json.contains("\"symbolVisibilityPolicy\": \"allowlistOnlyJniOnLoadAndBootstrap\""));
        assertTrue(json.contains("\"actualSha256\": \"" + "a".repeat(64) + "\""));
        assertTrue(json.contains("\"actualJarPath\": \"native/windows-x64/x64-windows.dll\""));
        assertTrue(json.contains("\"windowsPdbPolicy\": \"excludePdbFromJarAndReports\""));
        assertTrue(json.contains("\"exportedSymbols\": [\n          \"JNI_OnLoad\"\n        ]"));
        assertTrue(json.contains("\"bootstrapEvents\""));
    }

    @Test
    void writesSuccessfulResignAction() {
        String json = new PackagingReportWriter().packagingJson(
                Path.of("output/input.jar"),
                SignaturePolicy.RESIGN,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                new NativeBuildPlan(List.of()),
                List.of(),
                xyz.melodysky.packaging.JarPreservationReport.empty(),
                SignatureActionReport.resigned(true, List.of("META-INF/OLD.SF", "META-INF/OLD.RSA")));

        assertTrue(json.contains("\"signaturePolicy\": \"resign\""));
        assertTrue(json.contains("\"action\": \"resign\""));
        assertTrue(json.contains("\"reasonCode\": \"SIGNATURE_RESIGNED\""));
        assertTrue(json.contains("\"META-INF/OLD.SF\""));
    }
}
