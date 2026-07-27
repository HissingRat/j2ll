package xyz.melodysky.report;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.melodysky.packaging.NativeLoaderClassGenerator;
import xyz.melodysky.packaging.RuntimeLoaderPlan;

class ArtifactAuditTest {
    @TempDir
    Path temp;

    @Test
    void passesCleanJarAndStableNativeHashAudit() throws Exception {
        Path jar = temp.resolve("output.jar");
        byte[] nativeBytes = "native".getBytes(StandardCharsets.UTF_8);
        writeJar(jar, withMetadata(
                "native0/macos-arm64/arm64-macos.dylib",
                 sha256(nativeBytes),
                 Map.of(
                         "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8),
                         "native0/macos-arm64/arm64-macos.dylib", nativeBytes)));

        ArtifactAuditResult result = new ArtifactAudit().audit(
                temp,
                jar,
                "native0",
                List.of(new EmbeddedLibraryReport(
                        "macos-arm64",
                        "native0/macos-arm64/arm64-macos.dylib",
                        sha256(nativeBytes))),
                List.of("JNI_OnLoad", "j2ll_register"),
                List.of());

        assertTrue(result.passed(), result.checks().toString());
        String json = new ArtifactAuditReportWriter().json(result);
        assertTrue(json.contains("\"reasonCode\": \"NATIVE_LIBRARY_SHA256_MATCH\""));
        assertTrue(json.contains("\"reasonCode\": \"NATIVE_EXPORT_ALLOWLIST_PASSED\""));
        assertTrue(json.contains("\"reasonCode\": \"NO_EMBEDDED_METHOD_BYTECODE\""));
        assertTrue(json.contains("\"reasonCode\": \"NO_EMBEDDED_BYTECODE_WORKSPACE_SURFACES\""));
        assertTrue(json.contains("\"name\": \"surface.outputJarEntries\""));
        assertTrue(json.contains("\"name\": \"surface.nativeLibraryResources\""));
        assertTrue(json.contains("\"name\": \"surface.symbolAuditOutput\""));
    }

    @Test
    void rejectsLoaderWithRetiredEmbeddedBytecodeApi() throws Exception {
        Path jar = temp.resolve("output.jar");
        byte[] nativeBytes = "native".getBytes(StandardCharsets.UTF_8);
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(
                org.objectweb.asm.Opcodes.V17,
                org.objectweb.asm.Opcodes.ACC_PUBLIC,
                "native0/Loader",
                null,
                "java/lang/Object",
                null);
        writer.visitMethod(
                        org.objectweb.asm.Opcodes.ACC_PUBLIC
                                | org.objectweb.asm.Opcodes.ACC_STATIC
                                | org.objectweb.asm.Opcodes.ACC_NATIVE,
                        "defineHiddenFallback",
                        "()V",
                        null,
                        null)
                .visitEnd();
        writer.visitEnd();
        writeJar(jar, withMetadata(
                "native0/x64-linux.so",
                sha256(nativeBytes),
                Map.of(
                        "native0/Loader.class", writer.toByteArray(),
                        "native0/x64-linux.so", nativeBytes)));

        ArtifactAuditResult result = new ArtifactAudit().audit(
                temp,
                jar,
                "native0",
                List.of(new EmbeddedLibraryReport(
                        "linux-x64",
                        "native0/x64-linux.so",
                        sha256(nativeBytes))),
                List.of("JNI_OnLoad", "j2ll_register"),
                List.of());

        assertFalse(result.passed());
        String json = new ArtifactAuditReportWriter().json(result);
        assertTrue(json.contains("\"reasonCode\": \"RUNTIME_LOADER_CLASS_INVALID\""), json);
        assertTrue(json.contains("defineHiddenFallback()V"), json);
    }

    @Test
    void failsEmbeddedBytecodePdbWrongNativeHashHiddenExportAndPlaintext() throws Exception {
        Path jar = temp.resolve("output.jar");
        writeJar(jar, withMetadata(
                "native0/windows-x64/x64-windows.dll",
                "0000",
                Map.of(
                        "j2ll/generated/fallback/pkg/Fallback.class", new byte[] {1},
                        "obfuscator/src/Legacy.class", new byte[] {3},
                        "native0/windows-x64/x64-windows.dll", "native".getBytes(StandardCharsets.UTF_8),
                        "native0/windows-x64/x64-windows.pdb", new byte[] {2})));
        Path c = temp.resolve("intermediates/classes/pkg/Foo/c/class.c");
        Files.createDirectories(c.getParent());
        Files.writeString(c, "const char* s = \"secret\";");

        ArtifactAuditResult result = new ArtifactAudit().audit(
                temp,
                jar,
                "native0",
                List.of(new EmbeddedLibraryReport(
                        "windows-x64",
                        "native0/windows-x64/x64-windows.dll",
                        "0000")),
                List.of("JNI_OnLoad", "j2ll_f_hidden", "j2ll_cit_table", "Java_pkg_Foo_run"),
                List.of(SensitivePlaintextFact.of(
                        "secret",
                        "pkg/Foo#secret!()Ljava/lang/String;",
                        "STRING_ENCRYPTION",
                        List.of("generated-c"))));

        assertFalse(result.passed());
        String json = new ArtifactAuditReportWriter().json(result);
        for (String reason : List.of(
                "EMBEDDED_METHOD_BYTECODE_ENTRY",
                "WINDOWS_PDB_PACKAGED",
                "NATIVE_LIBRARY_SHA256_MISMATCH",
                "NATIVE_EXPORT_ALLOWLIST_FAILED",
                "LEGACY_OUTPUT_PATH_FOUND",
                "FORBIDDEN_PLAINTEXT_FOUND")) {
            assertTrue(json.contains("\"reasonCode\": \"" + reason + "\""), reason + "\n" + json);
        }
    }

    @Test
    void failsWhenFlatWorkspaceNativeDirectoryContainsWindowsPdb() throws Exception {
        Path jar = temp.resolve("output.jar");
        byte[] nativeBytes = "native".getBytes(StandardCharsets.UTF_8);
        writeJar(jar, withMetadata(
                "native0/x64-windows.dll",
                sha256(nativeBytes),
                Map.of("native0/x64-windows.dll", nativeBytes)));
        Path pdb = temp.resolve("native/x64-windows.pdb");
        Files.createDirectories(pdb.getParent());
        Files.write(pdb, new byte[] {1, 2, 3});

        ArtifactAuditResult result = new ArtifactAudit().audit(
                temp,
                jar,
                "native0",
                List.of(new EmbeddedLibraryReport(
                        "windows-x64",
                        "native0/x64-windows.dll",
                        sha256(nativeBytes))),
                List.of("JNI_OnLoad", "j2ll_register"),
                List.of());

        assertFalse(result.passed());
        String json = new ArtifactAuditReportWriter().json(result);
        assertTrue(json.contains("\"reasonCode\": \"WINDOWS_PDB_WORKSPACE_FOUND\""), json);
        assertTrue(json.contains("native/x64-windows.pdb"), json);
    }

    @Test
    void reportsObservedOnlySensitiveFactsWithoutFailingAudit() throws Exception {
        Path jar = temp.resolve("observed.jar");
        byte[] nativeBytes = "native".getBytes(StandardCharsets.UTF_8);
        writeJar(jar, withMetadata(
                "native0/macos-arm64/arm64-macos.dylib",
                sha256(nativeBytes),
                Map.of("native0/macos-arm64/arm64-macos.dylib", nativeBytes)));
        Path c = temp.resolve("intermediates/classes/pkg/Foo/c/class.c");
        Files.createDirectories(c.getParent());
        Files.writeString(c, "const char* s = \"template-secret\";");

        ArtifactAuditResult result = new ArtifactAudit().audit(
                temp,
                jar,
                "native0",
                List.of(new EmbeddedLibraryReport(
                        "macos-arm64",
                        "native0/macos-arm64/arm64-macos.dylib",
                        sha256(nativeBytes))),
                List.of("JNI_OnLoad", "j2ll_register"),
                List.of(SensitivePlaintextFact.of(
                                "template-secret",
                                "pkg/Foo#template!()Ljava/lang/String;",
                                "STRING_ENCRYPTION",
                                List.of("generated-c"))
                        .withAuditClassification(
                                "TEMPLATE_JNI_PATH",
                                "observedOnly",
                                "NON_BLOCKING_PATH_KIND_UNTIL_SURFACE_CONNECTED")));

        assertTrue(result.passed(), result.checks().toString());
        String json = new ArtifactAuditReportWriter().json(result);
        assertTrue(json.contains("\"observedOnlySensitiveFacts\""), json);
        assertTrue(json.contains("\"pathKind\": \"TEMPLATE_JNI_PATH\""), json);
        assertTrue(json.contains("\"gateMode\": \"observedOnly\""), json);
        assertTrue(json.contains("\"promotionReason\": \"metadataSensitiveObservedOnly\""), json);
        assertTrue(json.contains("\"reasonCode\": \"OBSERVED_ONLY_SENSITIVE_FACTS_REPORTED\""), json);
        assertFalse(json.contains("template-secret"), json);
    }

    @Test
    void templateStableSurfaceSensitiveFactsAreBlockingAcrossGeneratedCAndJarEntries() throws Exception {
        Path jar = temp.resolve("template-leak.jar");
        byte[] nativeBytes = "native".getBytes(StandardCharsets.UTF_8);
        writeJar(jar, withMetadata(
                "native0/macos-arm64/arm64-macos.dylib",
                sha256(nativeBytes),
                Map.of(
                        "pkg/Leaky.class", "template-leak".getBytes(StandardCharsets.ISO_8859_1),
                        "native0/macos-arm64/arm64-macos.dylib", nativeBytes)));
        Path c = temp.resolve("intermediates/classes/pkg/Leaky/c/class.c");
        Files.createDirectories(c.getParent());
        Files.writeString(c, "const char* leaked = \"template-leak\";");

        ArtifactAuditResult result = new ArtifactAudit().audit(
                temp,
                jar,
                "native0",
                List.of(new EmbeddedLibraryReport(
                        "macos-arm64",
                        "native0/macos-arm64/arm64-macos.dylib",
                        sha256(nativeBytes))),
                List.of("JNI_OnLoad", "j2ll_register"),
                List.of(SensitivePlaintextFact.of(
                                "template-leak",
                                "pkg/Leaky#<init>!()V",
                                "STRING_ENCRYPTION",
                                List.of("generated-c", "jar-entry"))
                        .withAuditClassification(
                                "TEMPLATE_JNI_PATH_STABLE_SURFACE",
                                "blocking",
                                "TEMPLATE_CONSTRUCTOR_BODY_STABLE_SURFACE")));

        assertFalse(result.passed());
        String json = new ArtifactAuditReportWriter().json(result);
        assertTrue(json.contains("\"pathKind\": \"TEMPLATE_JNI_PATH_STABLE_SURFACE\""), json);
        assertTrue(json.contains("\"gateMode\": \"blocking\""), json);
        assertTrue(json.contains("\"promotionReason\": \"templateStableSurface\""), json);
        assertTrue(json.contains("\"reasonCode\": \"FORBIDDEN_PLAINTEXT_FOUND\""), json);
        assertTrue(json.contains("\"reasonCode\": \"FORBIDDEN_PLAINTEXT_JAR_ENTRY\""), json);
        assertFalse(json.contains("template-leak"), json);
    }

    @Test
    void stringConcatConstantCarrierSensitiveFactsAreBlockingStableGeneratedCSurface() throws Exception {
        Path jar = temp.resolve("string-concat-leak.jar");
        byte[] nativeBytes = "native".getBytes(StandardCharsets.UTF_8);
        writeJar(jar, withMetadata(
                "native0/macos-arm64/arm64-macos.dylib",
                sha256(nativeBytes),
                Map.of("native0/macos-arm64/arm64-macos.dylib", nativeBytes)));
        Path c = temp.resolve("intermediates/classes/pkg/Concat/c/class.c");
        Files.createDirectories(c.getParent());
        Files.writeString(c, "const char* carrier = \"recipe-secret\";");

        ArtifactAuditResult result = new ArtifactAudit().audit(
                temp,
                jar,
                "native0",
                List.of(new EmbeddedLibraryReport(
                        "macos-arm64",
                        "native0/macos-arm64/arm64-macos.dylib",
                        sha256(nativeBytes))),
                List.of("JNI_OnLoad", "j2ll_register"),
                List.of(SensitivePlaintextFact.of(
                                "recipe-secret",
                                "pkg/Concat#concat!(Ljava/lang/String;I)Ljava/lang/String;",
                                "STRING_ENCRYPTION",
                                List.of("generated-c", "llvm-ir", "native-library"))
                        .withAuditClassification(
                                "HELPER_PATH_STABLE_GENERATED_C_SURFACE",
                                "blocking",
                                "STRING_CONCAT_CONSTANT_CARRIER_STABLE_SURFACE",
                                "stableGeneratedCSurface")));

        assertFalse(result.passed());
        String json = new ArtifactAuditReportWriter().json(result);
        assertTrue(json.contains("\"pathKind\": \"HELPER_PATH_STABLE_GENERATED_C_SURFACE\""), json);
        assertTrue(json.contains("\"promotionReason\": \"stableGeneratedCSurface\""), json);
        assertTrue(json.contains("\"reasonCode\": \"FORBIDDEN_PLAINTEXT_FOUND\""), json);
        assertFalse(json.contains("recipe-secret"), json);
    }

    @Test
    void reflectionAndLambdaMetadataSensitiveFactsRemainObservedOnly() throws Exception {
        Path jar = temp.resolve("metadata-observed.jar");
        byte[] nativeBytes = "native".getBytes(StandardCharsets.UTF_8);
        writeJar(jar, withMetadata(
                "native0/macos-arm64/arm64-macos.dylib",
                sha256(nativeBytes),
                Map.of("native0/macos-arm64/arm64-macos.dylib", nativeBytes)));
        Path c = temp.resolve("intermediates/classes/pkg/Meta/c/class.c");
        Files.createDirectories(c.getParent());
        Files.writeString(c, "const char* reflected = \"pkg.Private\"; const char* lambda = \"lambda$0\";");

        ArtifactAuditResult result = new ArtifactAudit().audit(
                temp,
                jar,
                "native0",
                List.of(new EmbeddedLibraryReport(
                        "macos-arm64",
                        "native0/macos-arm64/arm64-macos.dylib",
                        sha256(nativeBytes))),
                List.of("JNI_OnLoad", "j2ll_register"),
                List.of(
                        SensitivePlaintextFact.of(
                                        "pkg.Private",
                                        "pkg/Reflective#run!()V",
                                        "STRING_ENCRYPTION",
                                        List.of("generated-c"))
                                .withAuditClassification(
                                        "HELPER_PATH",
                                        "observedOnly",
                                        "NON_BLOCKING_PATH_KIND_UNTIL_SURFACE_CONNECTED",
                                        "metadataSensitiveObservedOnly"),
                        SensitivePlaintextFact.of(
                                        "lambda$0",
                                        "pkg/Lambda#run!()V",
                                        "STRING_ENCRYPTION",
                                        List.of("generated-c"))
                                .withAuditClassification(
                                        "HELPER_PATH",
                                        "observedOnly",
                                        "NON_BLOCKING_PATH_KIND_UNTIL_SURFACE_CONNECTED",
                                        "metadataSensitiveObservedOnly")));

        assertTrue(result.passed(), result.checks().toString());
        String json = new ArtifactAuditReportWriter().json(result);
        assertTrue(json.contains("\"reasonCode\": \"OBSERVED_ONLY_SENSITIVE_FACTS_REPORTED\""), json);
        assertTrue(json.contains("\"promotionReason\": \"metadataSensitiveObservedOnly\""), json);
        assertFalse(json.contains("pkg.Private"), json);
        assertFalse(json.contains("lambda$0"), json);
    }

    @Test
    void rejectsLegacyFallbackWorkspaceSurfaceAndPackagingMetadata() throws Exception {
        Path jar = temp.resolve("output.jar");
        byte[] nativeBytes = "native".getBytes(StandardCharsets.UTF_8);
        writeJar(jar, withMetadata(
                "native0/arm64-macos.dylib",
                sha256(nativeBytes),
                Map.of("native0/arm64-macos.dylib", nativeBytes)));
        Path carrier = temp.resolve("native/zig-workspace/fallback-blobs/encoded.bin");
        Files.createDirectories(carrier.getParent());
        Files.write(carrier, new byte[] {1, 2, 3});
        Path packagingReport = temp.resolve("reports/packaging-report.json");
        Files.createDirectories(packagingReport.getParent());
        Files.writeString(packagingReport, "{\"fallbackBlobs\":[]}\n");

        ArtifactAuditResult result = new ArtifactAudit().audit(
                temp,
                jar,
                "native0",
                List.of(new EmbeddedLibraryReport(
                        "macos-arm64",
                        "native0/arm64-macos.dylib",
                        sha256(nativeBytes))),
                List.of("JNI_OnLoad", "j2ll_register"),
                List.of());

        assertFalse(result.passed());
        String json = new ArtifactAuditReportWriter().json(result);
        assertTrue(json.contains("\"reasonCode\": \"EMBEDDED_BYTECODE_WORKSPACE_SURFACE\""), json);
        assertTrue(json.contains("native/zig-workspace/fallback-blobs"), json);
        assertTrue(json.contains("legacyFallbackMetadata"), json);
    }

    @Test
    void failsRawSeedInFinalJarMetadata() throws Exception {
        Path jar = temp.resolve("raw-seed.jar");
        byte[] nativeBytes = "native".getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> entries = withMetadata(
                "native0/macos-arm64/arm64-macos.dylib",
                sha256(nativeBytes),
                Map.of("native0/macos-arm64/arm64-macos.dylib", nativeBytes));
        entries.put("META-INF/j2ll/build-info.json", """
                {
                  "schemaVersion": 1,
                  "reportVersion": 1,
                  "configHash": "%s",
                  "protectionSeedHash": "%s",
                  "protectionSeed": "raw-secret-seed"
                }
                """.formatted("c".repeat(64), "d".repeat(64)).getBytes(StandardCharsets.UTF_8));
        writeJar(jar, entries);

        ArtifactAuditResult result = new ArtifactAudit().audit(
                temp,
                jar,
                "native0",
                List.of(new EmbeddedLibraryReport(
                        "macos-arm64",
                        "native0/macos-arm64/arm64-macos.dylib",
                        sha256(nativeBytes))),
                List.of("JNI_OnLoad", "j2ll_register"),
                List.of());

        assertFalse(result.passed());
        String json = new ArtifactAuditReportWriter().json(result);
        assertTrue(json.contains("\"reasonCode\": \"J2LL_METADATA_RAW_SEED\""), json);
        assertFalse(json.contains("raw-secret-seed"), json);
    }

    @Test
    void failsMissingReportsManifestEntry() throws Exception {
        Path jar = temp.resolve("bad-manifest.jar");
        byte[] nativeBytes = "native".getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> entries = withMetadata(
                "native0/macos-arm64/arm64-macos.dylib",
                sha256(nativeBytes),
                Map.of("native0/macos-arm64/arm64-macos.dylib", nativeBytes));
        entries.put("META-INF/j2ll/reports-manifest.json", """
                {
                  "schemaVersion": 1,
                  "reportVersion": 1,
                  "reports": ["diagnostics.json"],
                  "reportsManifestHash": "%s"
                }
                """.formatted(sha256("diagnostics.json".getBytes(StandardCharsets.UTF_8))).getBytes(StandardCharsets.UTF_8));
        writeJar(jar, entries);

        ArtifactAuditResult result = new ArtifactAudit().audit(
                temp,
                jar,
                "native0",
                List.of(new EmbeddedLibraryReport(
                        "macos-arm64",
                        "native0/macos-arm64/arm64-macos.dylib",
                        sha256(nativeBytes))),
                List.of("JNI_OnLoad", "j2ll_register"),
                List.of());

        assertFalse(result.passed());
        String json = new ArtifactAuditReportWriter().json(result);
        assertTrue(json.contains("\"reasonCode\": \"J2LL_REPORTS_MANIFEST_INCOMPLETE\""), json);
    }

    @Test
    void failsNativeLibrariesMetadataMismatchWithTargetArtifacts() throws Exception {
        Path jar = temp.resolve("metadata-mismatch.jar");
        byte[] nativeBytes = "native".getBytes(StandardCharsets.UTF_8);
        writeJar(jar, withMetadata(
                "native0/macos-arm64/arm64-macos.dylib",
                sha256(nativeBytes),
                Map.of("native0/macos-arm64/arm64-macos.dylib", nativeBytes)));
        writeTargetArtifactPackagingReport(
                "native0/macos-arm64/other.dylib",
                sha256(nativeBytes));

        ArtifactAuditResult result = new ArtifactAudit().audit(
                temp,
                jar,
                "native0",
                List.of(new EmbeddedLibraryReport(
                        "macos-arm64",
                        "native0/macos-arm64/arm64-macos.dylib",
                        sha256(nativeBytes))),
                List.of("JNI_OnLoad", "j2ll_register"),
                List.of());

        assertFalse(result.passed());
        String json = new ArtifactAuditReportWriter().json(result);
        assertTrue(json.contains("\"name\": \"metadata.nativeLibrariesTargetArtifacts\""), json);
        assertTrue(json.contains("\"reasonCode\": \"METADATA_CONSISTENCY_FAILED\""), json);
    }

    private void writeJar(Path jar, Map<String, byte[]> entries) throws Exception {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                JarEntry jarEntry = new JarEntry(entry.getKey());
                jarEntry.setTime(0L);
                output.putNextEntry(jarEntry);
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
    }

    private Map<String, byte[]> withMetadata(String jarPath, String sha256, Map<String, byte[]> entries) throws Exception {
        java.util.LinkedHashMap<String, byte[]> all = new java.util.LinkedHashMap<>(entries);
        all.putIfAbsent(
                "native0/Loader.class",
                new NativeLoaderClassGenerator().generate(
                        RuntimeLoaderPlan.create("native0"),
                        List.of()));
        List<String> reports = List.of(
                "diagnostics.json",
                "artifact-audit.json",
                "field-internalization-report.json",
                "skipped-method-report.json",
                "known-blockers.json",
                "lowering-report.json",
                "opcode-support-matrix.json",
                "packaging-report.json",
                "protection-report.json",
                "release-readiness.json",
                "index.json",
                "summary.json",
                "summary.md",
                "support-matrix.json",
                "symbol-audit.json");
        all.put("META-INF/j2ll/build-info.json", """
                {
                  "schemaVersion": 1,
                  "reportVersion": 1,
                  "configHash": "%s",
                  "protectionSeedHash": "%s"
                }
                """.formatted("c".repeat(64), "d".repeat(64)).getBytes(StandardCharsets.UTF_8));
        all.put("META-INF/j2ll/native-libraries.json", """
                {
                  "schemaVersion": 1,
                  "reportVersion": 1,
                  "libraries": [
                    {
                      "target": "fixture",
                      "jarPath": "%s",
                      "sha256": "%s"
                    }
                  ]
                }
                """.formatted(jarPath, sha256).getBytes(StandardCharsets.UTF_8));
        all.put("META-INF/j2ll/reports-manifest.json", """
                {
                  "schemaVersion": 1,
                  "reportVersion": 1,
                  "reports": %s,
                  "reportsManifestHash": "%s"
                }
                """.formatted(
                        reports.stream()
                                .map(value -> "\"" + value + "\"")
                                .collect(java.util.stream.Collectors.joining(", ", "[", "]")),
                        sha256(String.join("\n", reports).getBytes(StandardCharsets.UTF_8)))
                .getBytes(StandardCharsets.UTF_8));
        return all;
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private void writeTargetArtifactPackagingReport(String actualJarPath, String actualSha256) throws Exception {
        Path report = temp.resolve("reports/packaging-report.json");
        Files.createDirectories(report.getParent());
        Files.writeString(report, """
                {
                  "schemaVersion": 1,
                  "reportVersion": 1,
                  "zigToolchain": {
                    "targetArtifacts": [
                      {
                        "target": "macos-arm64",
                        "required": true,
                        "currentHost": true,
                        "buildable": true,
                        "osClassifier": "macos",
                        "archClassifier": "arm64",
                        "libraryExtension": "dylib",
                        "libraryName": "j2ll",
                        "zigTarget": "aarch64-macos.11.0",
                        "expectedArtifactPath": "native/arm64-macos.dylib",
                        "expectedArtifactName": "arm64-macos.dylib",
                        "expectedResourcePath": "%1$s",
                        "loaderExtractionPathPolicy": "contentAddressedTempCacheBySha256",
                        "symbolVisibilityPolicy": "allowlistOnlyJniOnLoadAndBootstrap",
                        "windowsPdbPolicy": "notApplicable",
                        "actualArtifactPath": "native/arm64-macos.dylib",
                        "actualJarPath": "%1$s",
                        "actualSha256": "%2$s",
                        "exportedSymbols": ["JNI_OnLoad"],
                        "status": "built",
                        "reasonCode": "CURRENT_HOST_TARGET",
                        "reason": "selected target matches the current JVM host and is buildable now",
                        "requiredCapability": "managedZig0.15.2CrossTargetSharedLibrary",
                        "platformSdkRequirement": "managed Zig 0.15.2 Mach-O/Darwin target support; no host macOS SDK required",
                        "failureKind": "none",
                        "buildLogTail": "preflight buildable; Zig build log is recorded after invocation"
                      }
                    ]
                  }
                }
                """.formatted(actualJarPath, actualSha256));
    }
}
