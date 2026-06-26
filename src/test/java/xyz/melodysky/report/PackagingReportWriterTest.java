package xyz.melodysky.report;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import xyz.melodysky.toolchain.HostPlatform;
import xyz.melodysky.toolchain.NativeBuildPlan;
import xyz.melodysky.toolchain.NativeBuildPlanner;
import xyz.melodysky.toolchain.TargetTriple;
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
                  "outputJar": "output/input.jar",
                  "manifestPolicy": "preserved",
                  "signaturePolicy": "fail",
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
                    "buildableTargets": [],
                    "skippedTargets": [],
                    "failedTargets": []
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
                "j2ll/generated/fallback/pkg_Foo/Fallback$run__8f3a21c0d4e5f607",
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
                "DefineClass",
                "lazyPerClassLoaderReuse");

        assertEquals("""
                {
                  "schemaVersion": 1,
                  "outputJar": "output/input.jar",
                  "manifestPolicy": "preserved",
                  "signaturePolicy": "fail",
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
                    "buildableTargets": [],
                    "skippedTargets": [],
                    "failedTargets": []
                  },
                  "registeredNativeMethods": [],
                  "registrationGroups": [],
                  "exportedSymbols": [],
                  "fallbackBlobs": [
                    {
                      "originalMethodId": "run__8f3a21c0d4e5f607",
                      "originalMethodKey": "pkg/Foo#run!()V",
                      "helperClassName": "j2ll/generated/fallback/pkg_Foo/Fallback$run__8f3a21c0d4e5f607",
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
                      "definitionMechanism": "DefineClass",
                      "definitionMechanismReasonCode": "FALLBACK_DEFINE_CLASS",
                      "classloaderReusePolicy": "lazyPerClassLoaderReuse"
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
    void writesSelectedTargetPreflightSummary() {
        NativeBuildPlan plan = new NativeBuildPlanner(Optional.of(new HostPlatform(TargetTriple.MACOS_ARM64, "darwin"))).plan(
                Path.of("/work"),
                "j2llapp",
                List.of(TargetTriple.LINUX_X64, TargetTriple.MACOS_ARM64));

        assertEquals("""
                {
                  "schemaVersion": 1,
                  "outputJar": "output/input.jar",
                  "manifestPolicy": "preserved",
                  "signaturePolicy": "fail",
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
                    "buildableTargets": [
                      "macos-arm64"
                    ],
                    "skippedTargets": [
                      {
                        "target": "linux-x64",
                        "zigTarget": "x86_64-linux",
                        "output": "/work/native/linux-x64/x64-linux.so",
                        "status": "skipped",
                        "currentHost": false,
                        "buildable": false,
                        "reasonCode": "NON_HOST_TARGET_PREFLIGHT_ONLY",
                        "reason": "selected target linux-x64 is recorded in the build plan, but this slice only builds the current host target",
                        "requiredCapability": "managedZig0.15.2BuildZigSharedLibrary",
                        "platformSdkRequirement": "Zig Linux libc/linker support for selected target"
                      }
                    ],
                    "failedTargets": []
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
}
