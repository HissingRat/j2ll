package xyz.melodysky.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import xyz.melodysky.config.ConfigLoader;
import xyz.melodysky.config.ResolvedConfig;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.JarClassFileSource;
import xyz.melodysky.report.ArtifactAudit;
import xyz.melodysky.report.ArtifactAuditCheck;
import xyz.melodysky.report.ArtifactAuditResult;
import xyz.melodysky.report.EmbeddedLibraryReport;
import xyz.melodysky.report.SensitivePlaintextFact;
import xyz.melodysky.testsupport.AsmFixtureBuilder;
import xyz.melodysky.testsupport.FakeManagedZig;
import xyz.melodysky.testsupport.JvmRunner;
import xyz.melodysky.toolchain.HostPlatform;
import xyz.melodysky.toolchain.TargetTriple;

class MainlinePipelineIntegrationTest implements Opcodes {
    @TempDir
    Path temp;

    @Test
    void runsTinyStaticAddJarThroughMainlineSkeleton() throws Exception {
        Path inputJar = temp.resolve("input.jar");
        writeJar(inputJar);
        ResolvedConfig config = config(inputJar);
        Path workspace = temp.resolve("out/build_2026-06-25_00-00-00");

        MainlinePipelineResult result = runPipeline(config, workspace);

        assertTrue(result.successful());
        assertTrue(Files.exists(result.outputJar()));
        assertRewrittenAddIsNative(result.outputJar());
        assertTrue(Files.readString(workspace.resolve("reports/lowering-report.json")).contains("\"status\": \"lowered\""));
        assertTrue(Files.readString(workspace.resolve("reports/lowering-report.json")).contains("\"rewriteStrategy\": \"nativeOriginal\""));
        assertTrue(Files.readString(workspace.resolve("reports/lowering-report.json")).contains("JNI_ABI_REGISTER_NATIVES"));
        assertTrue(Files.readString(workspace.resolve("reports/packaging-report.json")).contains("\"rewrittenClasses\""));
        assertTrue(Files.readString(workspace.resolve("reports/packaging-report.json")).contains("\"zigToolchain\""));
        assertTrue(Files.readString(workspace.resolve("reports/symbol-audit.json")).contains("\"status\": \"passed\""));
        assertTrue(Files.exists(workspace.resolve("native/zig-workspace/build.zig")));
        assertTrue(Files.exists(workspace.resolve("config.resolved.json")));
        assertTrue(Files.exists(workspace.resolve("reports/frontend-skip-report.json")));
        assertTrue(Files.exists(workspace.resolve("reports/protection-report.json")));
        assertTrue(Files.exists(workspace.resolve("reports/support-matrix.json")));
        assertTrue(Files.readString(workspace.resolve("intermediates/runtime/runtime-metadata.json"))
                .contains("\"reflectionReachability\""));
        Path classDir = Files.walk(workspace.resolve("intermediates/classes"))
                .filter(path -> path.getFileName().toString().startsWith("Mathy__"))
                .findFirst()
                .orElseThrow();
        assertTrue(Files.exists(classDir.resolve("class-index.json")));
        assertTrue(Files.exists(classDir.resolve("method-index.json")));
        assertTrue(Files.readString(classDir.resolve("llvm/class.ll")).contains("define external hidden i32"));
        assertFalse(result.nativeRegistrationPlan().entries().isEmpty());
        assertFalse(result.nativeBuildPlan().units().isEmpty());
    }

    @Test
    void intermediateArtifactManifestHonorsDisabledDumpFlags() throws Exception {
        Path inputJar = temp.resolve("intermediate-flags.jar");
        writeJar(inputJar);
        String selectorJson = "\"pkg/Mathy#add!(II)I\"";
        JsonObject json = JsonParser.parseString(baseJson(inputJar, selectorJson)).getAsJsonObject();
        JsonObject intermediates = json.getAsJsonObject("intermediates");
        intermediates.addProperty("includeDebugDumps", false);
        intermediates.addProperty("includePerClassIr", false);
        intermediates.addProperty("includePerClassLlvm", false);
        intermediates.addProperty("includePerClassC", false);
        ResolvedConfig config = new ConfigLoader().load(json, temp).config().orElseThrow();
        Path workspace = temp.resolve("out/intermediate-flags");

        MainlinePipelineResult result = runPipeline(config, workspace);

        assertTrue(result.successful(), result.diagnostics().toString());
        String manifest = Files.readString(workspace.resolve("intermediates/intermediates-manifest.json"));
        assertTrue(manifest.contains("\"includeDebugDumps\": false"));
        assertTrue(manifest.contains("\"includePerClassIr\": false"));
        assertTrue(manifest.contains("\"class\": \"pkg/Mathy\""));
        assertTrue(manifest.contains("\"methodId\": \"add__"));
        assertFalse(Files.exists(workspace.resolve("intermediates/runtime/runtime-metadata.json")));
        try (var stream = Files.walk(workspace.resolve("intermediates/classes"))) {
            List<String> written = stream.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .toList();
            assertFalse(written.stream().anyMatch(name -> name.endsWith(".cfg.txt")));
            assertFalse(written.stream().anyMatch(name -> name.endsWith(".ll")));
            assertFalse(written.stream().anyMatch(name -> name.endsWith(".c")));
            assertFalse(written.stream().anyMatch(name -> name.endsWith(".ssa.ir")));
        }
    }

    @Test
    void artifactAuditFailureDeletesFinalJarAndWritesFailureReports() throws Exception {
        Path inputJar = temp.resolve("audit-fail-input.jar");
        writeJar(inputJar);
        ResolvedConfig config = config(inputJar);
        Path workspace = temp.resolve("out/build_2026-06-25-audit-fail");

        MainlinePipelineResult result;
        try (AutoCloseable ignored = FakeManagedZig.installAndUse(temp.resolve("j2ll-home"))) {
            result = new MainlinePipeline(System::getenv, new ArtifactAudit() {
                @Override
                public ArtifactAuditResult audit(
                        Path workspaceRoot,
                        Path outputJar,
                        String embeddedLibraryDirectory,
                        List<EmbeddedLibraryReport> embeddedLibraries,
                        List<String> exportedSymbols,
                        List<SensitivePlaintextFact> sensitivePlaintextFacts) {
                    return new ArtifactAuditResult(false, List.of(ArtifactAuditCheck.failed(
                            "plaintext.forbiddenStrings",
                            "FORBIDDEN_PLAINTEXT_FOUND",
                            "test-injected artifact leak")));
                }
            }).run(config, workspace);
        }

        assertFalse(result.successful());
        assertFalse(Files.exists(result.outputJar()));
        String diagnostics = Files.readString(workspace.resolve("reports/diagnostics.json"));
        String failure = Files.readString(workspace.resolve("reports/failure-report.json"));
        String readiness = Files.readString(workspace.resolve("reports/release-readiness.json"));
        assertTrue(diagnostics.contains("ARTIFACT_AUDIT_FAILED"));
        assertTrue(failure.contains("\"stage\": \"ARTIFACT_AUDIT\""));
        assertTrue(failure.contains("\"reasonCode\": \"ARTIFACT_AUDIT_FAILED\""));
        assertTrue(readiness.contains("\"finalArtifactWritten\": false"));
    }

    @Test
    void reportsHalfLoweredFallbackAndNativeEmbeddedBlobWithoutSilentSkip() throws Exception {
        Path inputJar = temp.resolve("fallback-input.jar");
        writeJar(inputJar, "pkg/JdkFallback.class", AsmFixtureBuilder.classWithUnsupportedJdkStringCall("pkg/JdkFallback"));
        ResolvedConfig config = config(inputJar, "pkg/JdkFallback#substring!(Ljava/lang/String;)Ljava/lang/String;");
        Path workspace = temp.resolve("out/build_2026-06-25_00-00-01");

        MainlinePipelineResult result = runPipeline(config, workspace);

        assertTrue(result.successful());
        String loweringReport = Files.readString(workspace.resolve("reports/lowering-report.json"));
        String packagingReport = Files.readString(workspace.resolve("reports/packaging-report.json"));
        assertTrue(loweringReport.contains("\"status\": \"halfLowered\""));
        assertTrue(loweringReport.contains("\"fallbackMode\": \"nativeEmbeddedClassBlob\""));
        assertTrue(loweringReport.contains("JVM_HELPER_FALLBACK"));
        assertTrue(loweringReport.contains("JNI_ABI_REGISTER_NATIVES"));
        assertTrue(packagingReport.contains("\"fallbackBlobs\""));
        assertTrue(packagingReport.contains("\"storageTarget\": \"nativeEmbeddedClassBlob\""));
        assertTrue(packagingReport.contains("\"classloaderReusePolicy\": \"lazyPerClassLoaderReuse\""));
        assertFalse(packagingReport.contains(".class"));
    }

    @Test
    void reportsUnsupportedUnsafeRawMemoryWithStableFallbackReason() throws Exception {
        Path inputJar = temp.resolve("unsafe-raw-memory-input.jar");
        writeJar(inputJar, "pkg/UnsafeOps.class", AsmFixtureBuilder.classWithUnsafeMethods("pkg/UnsafeOps"));
        ResolvedConfig config = config(inputJar, "pkg/UnsafeOps#unsupported!(Lsun/misc/Unsafe;J)B");
        Path workspace = temp.resolve("out/build_2026-06-25_00-00-05");

        MainlinePipelineResult result = runPipeline(config, workspace);

        assertTrue(result.successful(), result.diagnostics().toString());
        String loweringReport = Files.readString(workspace.resolve("reports/lowering-report.json"));
        String packagingReport = Files.readString(workspace.resolve("reports/packaging-report.json"));
        assertTrue(loweringReport.contains("\"status\": \"halfLowered\""));
        assertTrue(loweringReport.contains("\"reasonCode\": \"UNSAFE_RAW_MEMORY_FALLBACK\""));
        assertTrue(loweringReport.contains("\"fallbackMode\": \"nativeEmbeddedClassBlob\""));
        assertTrue(packagingReport.contains("\"fallbackReasonCode\": \"UNSAFE_RAW_MEMORY_FALLBACK\""));
    }

    @Test
    void reportsComplexFinallyAsFrontendSkippedNotFallback() throws Exception {
        Path inputJar = temp.resolve("complex-finally-input.jar");
        writeJar(inputJar, "pkg/FinallyShape.class",
                AsmFixtureBuilder.classWithUnsupportedMultiExitFinallyShape("pkg/FinallyShape"));
        ResolvedConfig config = config(inputJar, "pkg/FinallyShape#badFinally!()V");
        Path workspace = temp.resolve("out/build_2026-06-25_00-00-03");

        MainlinePipelineResult result = runPipeline(config, workspace);

        assertTrue(result.successful(), result.diagnostics().toString());
        String loweringReport = Files.readString(workspace.resolve("reports/lowering-report.json"));
        String frontendSkipReport = Files.readString(workspace.resolve("reports/frontend-skip-report.json"));
        String packagingReport = Files.readString(workspace.resolve("reports/packaging-report.json"));
        assertTrue(loweringReport.contains("\"status\": \"frontendSkipped\""));
        assertTrue(loweringReport.contains("UNSUPPORTED_MULTI_EXIT_FINALLY"));
        assertTrue(frontendSkipReport.contains("UNSUPPORTED_MULTI_EXIT_FINALLY"));
        assertFalse(loweringReport.contains("\"fallbackMode\": \"nativeEmbeddedClassBlob\""));
        assertTrue(packagingReport.contains("\"fallbackBlobs\": []"));
        assertTrue(packagingReport.contains("\"registeredNativeMethods\": []"));
        var outputClass = new AsmClassParser()
                .parseAll(new JarClassFileSource(result.outputJar()))
                .artifact()
                .orElseThrow()
                .program()
                .findClass("pkg/FinallyShape")
                .orElseThrow();
        var badFinally = outputClass.methods().stream()
                .filter(method -> method.name().equals("badFinally"))
                .findFirst()
                .orElseThrow();
        assertFalse(badFinally.accessFlags().isNative());
        assertTrue(badFinally.hasCode());
    }

    @Test
    void notApplicableMethodsAreNeverRewrittenOrRegistered() throws Exception {
        Path inputJar = temp.resolve("not-applicable-input.jar");
        writeJar(inputJar, Map.of(
                "pkg/AbstractApi.class", AsmFixtureBuilder.classWithVoidMethod(
                        "pkg/AbstractApi",
                        "java/lang/Object",
                        null,
                        ACC_PUBLIC | ACC_ABSTRACT | ACC_SUPER,
                        "call",
                        ACC_PUBLIC | ACC_ABSTRACT),
                "pkg/NativeApi.class", AsmFixtureBuilder.classWithVoidMethod(
                        "pkg/NativeApi",
                        "java/lang/Object",
                        null,
                        ACC_PUBLIC | ACC_SUPER,
                        "call",
                        ACC_PUBLIC | ACC_NATIVE),
                "pkg/NoCodeApi.class", noCodeMethodClass()));
        ResolvedConfig config = config(inputJar, List.of(
                "pkg/AbstractApi#call!()V",
                "pkg/NativeApi#call!()V",
                "pkg/NoCodeApi#call!()V"));
        Path workspace = temp.resolve("out/build_2026-06-25_00-00-04");

        MainlinePipelineResult result = runPipeline(config, workspace);

        assertTrue(result.successful(), result.diagnostics().toString());
        assertTrue(Files.exists(result.outputJar()));
        String loweringReport = Files.readString(workspace.resolve("reports/lowering-report.json"));
        String packagingReport = Files.readString(workspace.resolve("reports/packaging-report.json"));
        assertEquals(3, countOccurrences(loweringReport, "\"status\": \"notApplicable\""));
        assertTrue(loweringReport.contains("\"reasonCode\": \"ABSTRACT_METHOD\""));
        assertTrue(loweringReport.contains("\"reasonCode\": \"ALREADY_NATIVE\""));
        assertTrue(loweringReport.contains("\"reasonCode\": \"NO_CODE\""));
        assertTrue(packagingReport.contains("\"registeredNativeMethods\": []"));
        assertFalse(packagingReport.contains("\"registrationOwner\": \"pkg/AbstractApi\""));
        assertFalse(packagingReport.contains("\"registrationOwner\": \"pkg/NativeApi\""));
        assertFalse(packagingReport.contains("\"registrationOwner\": \"pkg/NoCodeApi\""));
        var program = new AsmClassParser()
                .parseAll(new JarClassFileSource(result.outputJar()))
                .artifact()
                .orElseThrow()
                .program();
        var abstractCall = program.findClass("pkg/AbstractApi").orElseThrow().methods().stream()
                .filter(method -> method.name().equals("call"))
                .findFirst()
                .orElseThrow();
        var nativeCall = program.findClass("pkg/NativeApi").orElseThrow().methods().stream()
                .filter(method -> method.name().equals("call"))
                .findFirst()
                .orElseThrow();
        var noCodeCall = program.findClass("pkg/NoCodeApi").orElseThrow().methods().stream()
                .filter(method -> method.name().equals("call"))
                .findFirst()
                .orElseThrow();
        assertTrue(abstractCall.accessFlags().isAbstract());
        assertFalse(abstractCall.hasCode());
        assertTrue(nativeCall.accessFlags().isNative());
        assertFalse(nativeCall.hasCode());
        assertFalse(noCodeCall.accessFlags().isNative());
        assertFalse(noCodeCall.hasCode());
    }

    @Test
    void preservesServicesModuleInfoAndMultiReleaseMetadataInRunnableOutputJar() throws Exception {
        Path inputJar = temp.resolve("service-input.jar");
        writeJar(inputJar, serviceJarEntries(false));
        ResolvedConfig config = config(inputJar, "pkg/Provider#name!()Ljava/lang/String;");
        Path workspace = temp.resolve("out/build_2026-06-25_00-00-06");

        MainlinePipelineResult result = runPipeline(config, workspace);
        var run = new JvmRunner().run(result.outputJar(), "pkg.ServiceMain", List.of());

        assertTrue(result.successful(), result.diagnostics().toString());
        assertEquals(0, run.exitCode(), run.stderr());
        assertEquals("provided\n", run.stdout());
        try (JarFile jarFile = new JarFile(result.outputJar().toFile(), false)) {
            assertTrue(jarFile.getManifest().getMainAttributes().getValue("Multi-Release").equals("true"));
            assertTrue(jarFile.getJarEntry("META-INF/services/pkg.Service") != null);
            assertTrue(jarFile.getJarEntry("module-info.class") != null);
            assertTrue(jarFile.getJarEntry("META-INF/versions/9/resource.txt") != null);
        }
        String packagingReport = Files.readString(workspace.resolve("reports/packaging-report.json"));
        assertTrue(packagingReport.contains("\"manifestPreserved\": true"));
        assertTrue(packagingReport.contains("\"serviceEntriesPreserved\": 1"));
        assertTrue(packagingReport.contains("\"moduleInfoPreserved\": true"));
        assertTrue(packagingReport.contains("\"multiRelease\": true"));
        assertTrue(packagingReport.contains("\"versionedEntriesPreserved\": 1"));
        assertTrue(packagingReport.contains("\"versionedClassPolicy\": \"baseClassesOnlyPreserveVersionedEntries\""));
    }

    @Test
    void multiReleaseVersionedClassWinsAtRuntimeAfterBaseClassRewrite() throws Exception {
        Path inputJar = temp.resolve("multi-release-service-input.jar");
        Map<String, byte[]> entries = multiReleaseServiceJarEntries();
        writeJar(inputJar, entries);
        ResolvedConfig config = config(inputJar, "pkg/MrValue#value!()Ljava/lang/String;");
        Path workspace = temp.resolve("out/build_2026-06-25_00-00-06b");

        MainlinePipelineResult result = runPipeline(config, workspace);
        var run = new JvmRunner().run(result.outputJar(), "pkg.MultiReleaseServiceMain", List.of());

        assertTrue(result.successful(), result.diagnostics().toString());
        assertEquals(0, run.exitCode(), run.stderr());
        assertEquals("provided\nv9\n", run.stdout());
        try (JarFile jarFile = new JarFile(result.outputJar().toFile(), false)) {
            assertTrue(jarFile.getManifest().getMainAttributes().getValue("Multi-Release").equals("true"));
            assertTrue(jarFile.getJarEntry("META-INF/services/pkg.Service") != null);
            assertTrue(jarFile.getJarEntry("module-info.class") != null);
            assertTrue(jarFile.getJarEntry("META-INF/versions/9/pkg/MrValue.class") != null);
            try (java.io.InputStream input = jarFile.getInputStream(
                    jarFile.getJarEntry("META-INF/versions/9/pkg/MrValue.class"))) {
                assertEquals(
                        java.util.HexFormat.of().formatHex(entries.get("META-INF/versions/9/pkg/MrValue.class")),
                        java.util.HexFormat.of().formatHex(input.readAllBytes()));
            }
        }
        String packagingReport = Files.readString(workspace.resolve("reports/packaging-report.json"));
        assertTrue(packagingReport.contains("\"versionedEntriesPreserved\": 1"));
        assertTrue(packagingReport.contains("\"versionedClassPolicy\": \"baseClassesOnlyPreserveVersionedEntries\""));
    }

    @Test
    void signedInputFailsBeforeRewriteWhenSignaturePolicyIsFail() throws Exception {
        Path inputJar = temp.resolve("signed-fail-input.jar");
        writeJar(inputJar, serviceJarEntries(true));
        ResolvedConfig config = configWithSignaturePolicy(inputJar, "pkg/Provider#name!()Ljava/lang/String;", "fail");
        Path workspace = temp.resolve("out/build_2026-06-25_00-00-07");

        MainlinePipelineResult result = runPipeline(config, workspace);

        assertFalse(result.successful(), result.diagnostics().toString());
        assertFalse(Files.exists(result.outputJar()));
        String diagnostics = Files.readString(workspace.resolve("reports/diagnostics.json"));
        String packagingReport = Files.readString(workspace.resolve("reports/packaging-report.json"));
        assertTrue(diagnostics.contains("\"code\": \"SIGNED_INPUT_REJECTED\""));
        assertTrue(packagingReport.contains("\"action\": \"fail\""));
        assertTrue(packagingReport.contains("\"signedInput\": true"));
        assertTrue(packagingReport.contains("\"reasonCode\": \"SIGNED_INPUT_REJECTED\""));
        assertFalse(Files.exists(workspace.resolve("native/zig-workspace/build.zig")));
    }

    @Test
    void signedInputStripRemovesSignatureFilesAndOutputJarRuns() throws Exception {
        Path inputJar = temp.resolve("signed-strip-input.jar");
        writeJar(inputJar, serviceJarEntries(true));
        ResolvedConfig config = configWithSignaturePolicy(inputJar, "pkg/Provider#name!()Ljava/lang/String;", "strip");
        Path workspace = temp.resolve("out/build_2026-06-25_00-00-08");

        MainlinePipelineResult result = runPipeline(config, workspace);
        var run = new JvmRunner().run(result.outputJar(), "pkg.ServiceMain", List.of());

        assertTrue(result.successful(), result.diagnostics().toString());
        assertEquals(0, run.exitCode(), run.stderr());
        assertEquals("provided\n", run.stdout());
        try (JarFile jarFile = new JarFile(result.outputJar().toFile(), false)) {
            assertTrue(jarFile.getJarEntry("META-INF/TEST.SF") == null);
            assertTrue(jarFile.getJarEntry("META-INF/TEST.RSA") == null);
            assertTrue(jarFile.getJarEntry("META-INF/services/pkg.Service") != null);
        }
        String diagnostics = Files.readString(workspace.resolve("reports/diagnostics.json"));
        String packagingReport = Files.readString(workspace.resolve("reports/packaging-report.json"));
        assertTrue(diagnostics.contains("\"code\": \"SIGNATURE_STRIPPED\""));
        assertTrue(packagingReport.contains("\"action\": \"strip\""));
        assertTrue(packagingReport.contains("\"reasonCode\": \"SIGNATURE_STRIPPED\""));
        assertTrue(packagingReport.contains("\"META-INF/TEST.SF\""));
        assertTrue(packagingReport.contains("\"META-INF/TEST.RSA\""));
    }

    @Test
    void resignPolicyInvalidKeystoreFailsBeforeRewriteWithoutFinalJar() throws Exception {
        Path inputJar = temp.resolve("signed-resign-input.jar");
        writeJar(inputJar, serviceJarEntries(true));
        JsonObject json = JsonParser.parseString(baseJson(inputJar, "\"pkg/Provider#name!()Ljava/lang/String;\""))
                .getAsJsonObject();
        json.addProperty("signaturePolicy", "resign");
        json.add("signing", JsonParser.parseString("""
                {
                  "keystorePath": "missing-keystore.p12",
                  "storePasswordEnv": "J2LL_TEST_STORE_PASS",
                  "keyAlias": "j2ll",
                  "keyPasswordEnv": "J2LL_TEST_KEY_PASS",
                  "tsaUrl": null
                }
                """).getAsJsonObject());
        ResolvedConfig config = new ConfigLoader().load(json, temp).config().orElseThrow();
        Path workspace = temp.resolve("out/build_2026-06-25_00-00-09");

        MainlinePipelineResult result = runPipeline(config, workspace);

        assertFalse(result.successful(), result.diagnostics().toString());
        assertFalse(Files.exists(result.outputJar()));
        String diagnostics = Files.readString(workspace.resolve("reports/diagnostics.json"));
        String packagingReport = Files.readString(workspace.resolve("reports/packaging-report.json"));
        assertTrue(diagnostics.contains("\"code\": \"SIGNATURE_RESIGN_FAILED\""));
        assertTrue(packagingReport.contains("\"action\": \"resignFailed\""));
        assertTrue(packagingReport.contains("\"signedInput\": true"));
        assertTrue(packagingReport.contains("\"reasonCode\": \"SIGNATURE_RESIGN_INVALID_KEYSTORE\""));
        assertTrue(packagingReport.contains("\"META-INF/TEST.SF\""));
        assertFalse(Files.exists(workspace.resolve("native/zig-workspace/build.zig")));
    }

    @Test
    void resignPolicySignsOutputJarAndReplacesOldSignatureFilesWhenJdkSignerIsAvailable() throws Exception {
        Path keytool = jdkTool("keytool");
        Path jarsigner = jdkTool("jarsigner");
        assumeTrue(Files.isExecutable(keytool), "keytool is unavailable in this JDK");
        assumeTrue(Files.isExecutable(jarsigner), "jarsigner is unavailable in this JDK");
        Path keystore = temp.resolve("pipeline-signing.p12");
        generateKeystore(keytool, keystore);
        Path inputJar = temp.resolve("signed-resign-success-input.jar");
        writeJar(inputJar, serviceJarEntries(true));
        JsonObject json = JsonParser.parseString(baseJson(inputJar, "\"pkg/Provider#name!()Ljava/lang/String;\""))
                .getAsJsonObject();
        json.addProperty("signaturePolicy", "resign");
        json.add("signing", JsonParser.parseString("""
                {
                  "keystorePath": "pipeline-signing.p12",
                  "storePasswordEnv": "J2LL_TEST_STORE_PASS",
                  "keyAlias": "j2ll",
                  "keyPasswordEnv": "J2LL_TEST_KEY_PASS",
                  "tsaUrl": null
                }
                """).getAsJsonObject());
        ResolvedConfig config = new ConfigLoader().load(json, temp).config().orElseThrow();
        Path workspace = temp.resolve("out/build_2026-06-25_00-00-10");

        MainlinePipelineResult result;
        try (AutoCloseable ignored = FakeManagedZig.installAndUse(temp.resolve("j2ll-home"))) {
            result = new MainlinePipeline(Map.of(
                            "J2LL_TEST_STORE_PASS", "changeit",
                            "J2LL_TEST_KEY_PASS", "changeit")::get)
                    .run(config, workspace);
        }
        var run = new JvmRunner().run(result.outputJar(), "pkg.ServiceMain", List.of());

        assertTrue(result.successful(), result.diagnostics().toString());
        assertEquals(0, run.exitCode(), run.stderr());
        assertEquals("provided\n", run.stdout());
        assertEquals(0, verifyJar(jarsigner, result.outputJar()), "jarsigner -verify should accept the output JAR");
        try (JarFile jarFile = new JarFile(result.outputJar().toFile(), true)) {
            assertTrue(jarFile.getJarEntry("META-INF/TEST.SF") == null);
            assertTrue(jarFile.getJarEntry("META-INF/TEST.RSA") == null);
            assertTrue(jarFile.getJarEntry("META-INF/J2LL.SF") != null);
        }
        String packagingReport = Files.readString(workspace.resolve("reports/packaging-report.json"));
        assertTrue(packagingReport.contains("\"action\": \"resign\""));
        assertTrue(packagingReport.contains("\"reasonCode\": \"SIGNATURE_RESIGNED\""));
        assertTrue(packagingReport.contains("\"META-INF/TEST.SF\""));
    }

    @Test
    void failsWhenSelectedRequiredNonHostTargetIsUnbuildable() throws Exception {
        Path inputJar = temp.resolve("multi-target-input.jar");
        writeJar(inputJar);
        JsonObject json = JsonParser.parseString(baseJson(inputJar, "\"pkg/Mathy#add!(II)I\"")).getAsJsonObject();
        json.add("target", JsonParser.parseString(hostPlusNonHostTargetJson()).getAsJsonObject());
        ResolvedConfig config = new ConfigLoader().load(json, temp).config().orElseThrow();
        Path workspace = temp.resolve("out/build_2026-06-25_00-00-02");

        MainlinePipelineResult result = runPipeline(config, workspace);

        assertFalse(result.successful(), result.diagnostics().toString());
        assertFalse(Files.exists(result.outputJar()));
        assertEquals(1, result.nativeBuildPlan().units().size());
        assertEquals(2, result.nativeBuildPlan().targetPreflights().size());
        String diagnostics = Files.readString(workspace.resolve("reports/diagnostics.json"));
        String packagingReport = Files.readString(workspace.resolve("reports/packaging-report.json"));
        String manifest = Files.readString(workspace.resolve("native/zig-workspace/j2ll-build-manifest.json"));
        TargetTriple host = HostPlatform.detect().orElseThrow().target();
        TargetTriple nonHost = nonHostTarget(host);
        assertTrue(diagnostics.contains("\"code\": \"ZIG_TARGET_UNBUILDABLE\""));
        assertTrue(diagnostics.contains("\"decision\": \"failed\""));
        assertTrue(packagingReport.contains("\"selectedTargets\""));
        assertTrue(packagingReport.contains("\"requiredTargets\""));
        assertTrue(packagingReport.contains("\"buildableTargets\""));
        assertTrue(packagingReport.contains("\"failedTargets\""));
        assertTrue(packagingReport.contains("\"targetArtifacts\""));
        assertTrue(packagingReport.contains("\"expectedArtifactName\""));
        assertTrue(packagingReport.contains("\"windowsPdbPolicy\""));
        assertTrue(packagingReport.contains("\"target\": \"" + nonHost.directoryName() + "\""));
        assertTrue(packagingReport.contains("\"reasonCode\": \"ZIG_TARGET_UNBUILDABLE\""));
        assertTrue(packagingReport.contains("\"requiredCapability\": \"managedZig0.15.2BuildZigSharedLibrary\""));
        assertTrue(packagingReport.contains("\"platformSdkRequirement\""));
        assertTrue(packagingReport.contains("\"output\""));
        assertTrue(manifest.contains("\"target\": \"" + host.directoryName() + "\""));
        assertTrue(manifest.contains("\"target\": \"" + nonHost.directoryName() + "\""));
        assertFalse(Files.readString(workspace.resolve("native/zig-workspace/build.zig"))
                .contains("const target_" + nonHost.safeSymbol()));
    }

    @Test
    void unsupportedBoundaryReasonCodesAreDocumentedAndStable() throws Exception {
        String docs = Files.readString(Path.of("AGENTS.md"))
                + Files.readString(Path.of("docs/rewrite-roadmap.md"))
                + Files.readString(Path.of("docs/java-support-tiers.md"))
                + Files.readString(Path.of("docs/io-config-output-contract.md"))
                + Files.readString(Path.of("docs/pipeline/04-callgraph-runtime-analysis.md"))
                + Files.readString(Path.of("docs/pipeline/05-bytecode-to-ssa.md"))
                + Files.readString(Path.of("docs/pipeline/07-llvm-backend.md"))
                + Files.readString(Path.of("docs/pipeline/10-packaging-native-registration.md"))
                + Files.readString(Path.of("docs/pipeline/11-tier5-runtime-metadata-reflection-jni-unsafe.md"));

        for (String reasonCode : List.of(
                "UNSUPPORTED_DEFAULT_INTERFACE_SUPER",
                "UNSUPPORTED_MULTI_EXIT_FINALLY",
                "UNSUPPORTED_EXCEPTION_STATE_MERGE",
                "UNSUPPORTED_MONITOR_FINALLY_INTERACTION",
                "REFLECTION_DYNAMIC_FALLBACK",
                "UNSAFE_RAW_MEMORY_FALLBACK",
                "METHOD_HANDLE_PERMUTE_FALLBACK",
                "METHOD_HANDLE_FILTER_FALLBACK",
                "METHOD_HANDLE_FOLD_FALLBACK",
                "METHOD_HANDLE_COLLECTOR_UNSUPPORTED",
                "ALT_METAFACTORY_FALLBACK",
                "ZIG_TARGET_UNBUILDABLE")) {
            assertTrue(docs.contains(reasonCode), reasonCode + " must be documented");
        }
    }

    private void assertRewrittenAddIsNative(Path outputJar) throws IOException {
        var parsed = new AsmClassParser()
                .parseAll(new JarClassFileSource(outputJar))
                .artifact()
                .orElseThrow()
                .program()
                .findClass("pkg/Mathy")
                .orElseThrow();
        var add = parsed.methods().stream()
                .filter(method -> method.name().equals("add"))
                .findFirst()
                .orElseThrow();
        assertTrue(add.accessFlags().isNative());
        assertFalse(add.hasCode());
    }

    private void writeJar(Path inputJar) throws IOException {
        writeJar(inputJar, "pkg/Mathy.class", AsmFixtureBuilder.classWithAddMethod("pkg/Mathy"));
    }

    private void writeJar(Path inputJar, String classEntryName, byte[] classBytes) throws IOException {
        writeJar(inputJar, Map.of(classEntryName, classBytes));
    }

    private void writeJar(Path inputJar, Map<String, byte[]> entries) throws IOException {
        Map<String, byte[]> stableEntries = new LinkedHashMap<>(entries);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(inputJar))) {
            for (Map.Entry<String, byte[]> entry : stableEntries.entrySet()) {
                JarEntry classEntry = new JarEntry(entry.getKey());
                classEntry.setTime(0L);
                output.putNextEntry(classEntry);
                output.write(entry.getValue());
                output.closeEntry();
            }
            JarEntry resource = new JarEntry("data.txt");
            resource.setTime(0L);
            output.putNextEntry(resource);
            output.write("resource".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    private ResolvedConfig config(Path inputJar) {
        return config(inputJar, "pkg/Mathy#add!(II)I");
    }

    private ResolvedConfig config(Path inputJar, String selector) {
        return config(inputJar, List.of(selector));
    }

    private ResolvedConfig config(Path inputJar, List<String> selectors) {
        String selectorJson = selectors.stream()
                .map(selector -> "\"" + selector + "\"")
                .collect(java.util.stream.Collectors.joining(", "));
        JsonObject json = JsonParser.parseString(baseJson(inputJar, selectorJson)).getAsJsonObject();
        return new ConfigLoader().load(json, temp).config().orElseThrow();
    }

    private ResolvedConfig configWithSignaturePolicy(Path inputJar, String selector, String signaturePolicy) {
        JsonObject json = JsonParser.parseString(baseJson(inputJar, "\"" + selector + "\"")).getAsJsonObject();
        json.addProperty("signaturePolicy", signaturePolicy);
        return new ConfigLoader().load(json, temp).config().orElseThrow();
    }

    private Map<String, byte[]> serviceJarEntries(boolean signed) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/MANIFEST.MF", """
                Manifest-Version: 1.0\r
                Main-Class: pkg.ServiceMain\r
                Multi-Release: true\r
                \r
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        entries.put("pkg/Service.class", serviceInterfaceClass());
        entries.put("pkg/Provider.class", serviceProviderClass());
        entries.put("pkg/ServiceMain.class", serviceMainClass());
        entries.put("META-INF/services/pkg.Service", "pkg.Provider\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        entries.put("module-info.class", moduleInfoClass());
        entries.put("META-INF/versions/9/resource.txt", "v9\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (signed) {
            entries.put("META-INF/TEST.SF", "Signature-Version: 1.0\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            entries.put("META-INF/TEST.RSA", new byte[] {1, 2, 3, 4});
        }
        return entries;
    }

    private Map<String, byte[]> multiReleaseServiceJarEntries() {
        Map<String, byte[]> entries = serviceJarEntries(false);
        entries.put("META-INF/MANIFEST.MF", """
                Manifest-Version: 1.0\r
                Main-Class: pkg.MultiReleaseServiceMain\r
                Multi-Release: true\r
                \r
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        entries.remove("META-INF/versions/9/resource.txt");
        entries.put("pkg/MrValue.class", mrValueClass("base"));
        entries.put("META-INF/versions/9/pkg/MrValue.class", mrValueClass("v9"));
        entries.put("pkg/MultiReleaseServiceMain.class", multiReleaseServiceMainClass());
        return entries;
    }

    private byte[] noCodeMethodClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/NoCodeApi", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC, "call", "()V", null, null);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] serviceInterfaceClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, "pkg/Service", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "name", "()Ljava/lang/String;", null, null);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] serviceProviderClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/Provider", null, "java/lang/Object", new String[] {"pkg/Service"});
        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        MethodVisitor name = writer.visitMethod(ACC_PUBLIC, "name", "()Ljava/lang/String;", null, null);
        name.visitCode();
        name.visitLdcInsn("provided");
        name.visitInsn(ARETURN);
        name.visitMaxs(0, 0);
        name.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] serviceMainClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/ServiceMain", null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        MethodVisitor main = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null);
        main.visitCode();
        main.visitLdcInsn(org.objectweb.asm.Type.getObjectType("pkg/Service"));
        main.visitMethodInsn(
                INVOKESTATIC,
                "java/util/ServiceLoader",
                "load",
                "(Ljava/lang/Class;)Ljava/util/ServiceLoader;",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/util/ServiceLoader", "iterator", "()Ljava/util/Iterator;", false);
        main.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
        main.visitTypeInsn(CHECKCAST, "pkg/Service");
        main.visitMethodInsn(INVOKEINTERFACE, "pkg/Service", "name", "()Ljava/lang/String;", true);
        main.visitVarInsn(ASTORE, 1);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitVarInsn(ALOAD, 1);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitInsn(RETURN);
        main.visitMaxs(0, 0);
        main.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] mrValueClass(String value) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/MrValue", null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "value", "()Ljava/lang/String;", null, null);
        method.visitCode();
        method.visitLdcInsn(value);
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] multiReleaseServiceMainClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/MultiReleaseServiceMain", null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        MethodVisitor main = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null);
        main.visitCode();
        main.visitLdcInsn(org.objectweb.asm.Type.getObjectType("pkg/Service"));
        main.visitMethodInsn(
                INVOKESTATIC,
                "java/util/ServiceLoader",
                "load",
                "(Ljava/lang/Class;)Ljava/util/ServiceLoader;",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/util/ServiceLoader", "iterator", "()Ljava/util/Iterator;", false);
        main.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
        main.visitTypeInsn(CHECKCAST, "pkg/Service");
        main.visitMethodInsn(INVOKEINTERFACE, "pkg/Service", "name", "()Ljava/lang/String;", true);
        main.visitVarInsn(ASTORE, 1);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitVarInsn(ALOAD, 1);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitMethodInsn(INVOKESTATIC, "pkg/MrValue", "value", "()Ljava/lang/String;", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitInsn(RETURN);
        main.visitMaxs(0, 0);
        main.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] moduleInfoClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V17, ACC_MODULE, "module-info", null, null, null);
        var module = writer.visitModule("j2ll.fixture", 0, null);
        module.visitExport("pkg", 0);
        module.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private MainlinePipelineResult runPipeline(ResolvedConfig config, Path workspace) throws Exception {
        try (AutoCloseable ignored = FakeManagedZig.installAndUse(temp.resolve("j2ll-home"))) {
            return new MainlinePipeline().run(config, workspace);
        }
    }

    private void generateKeystore(Path keytool, Path keystore) throws Exception {
        Process process = new ProcessBuilder(
                        keytool.toString(),
                        "-genkeypair",
                        "-alias", "j2ll",
                        "-keyalg", "RSA",
                        "-keysize", "2048",
                        "-validity", "1",
                        "-storetype", "PKCS12",
                        "-keystore", keystore.toString(),
                        "-storepass", "changeit",
                        "-keypass", "changeit",
                        "-dname", "CN=j2ll test, OU=tests, O=melodysky, L=test, ST=test, C=US",
                        "-noprompt")
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
    }

    private int verifyJar(Path jarsigner, Path jar) throws Exception {
        Process process = new ProcessBuilder(jarsigner.toString(), "-verify", jar.toString())
                .redirectErrorStream(true)
                .start();
        process.getInputStream().readAllBytes();
        return process.waitFor();
    }

    private Path jdkTool(String name) {
        String executable = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? name + ".exe"
                : name;
        return Path.of(System.getProperty("java.home"), "bin", executable);
    }

    private String baseJson(Path inputJar, String selectorJson) {
        return """
                {
                  "schemaVersion": 1,
                  "jarFile": "%s",
                  "classPath": [],
                  "javaHome": null,
                  "runtimeImage": null,
                  "worldModel": "PARTIAL_WORLD",
                  "javaSupportTier": "TIER_5",
                  "fallbackMode": "nativeEmbeddedClassBlob",
                  "outputDirectory": "out",
                  "whiteList": [%s],
                  "blackList": [],
                  "target": %s,
                  "libraryName": "j2lltest",
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
                """.formatted(inputJar.toString().replace("\\", "\\\\"), selectorJson, hostTargetJson());
    }

    private String hostTargetJson() {
        TargetTriple target = HostPlatform.detect().orElseThrow().target();
        return """
                {
                    "windowsX64": %s,
                    "windowsArm64": %s,
                    "linuxX64": %s,
                    "linuxArm64": %s,
                    "macosX64": %s,
                    "macosArm64": %s
                  }""".formatted(
                target == TargetTriple.WINDOWS_X64,
                target == TargetTriple.WINDOWS_ARM64,
                target == TargetTriple.LINUX_X64,
                target == TargetTriple.LINUX_ARM64,
                target == TargetTriple.MACOS_X64,
                target == TargetTriple.MACOS_ARM64);
    }

    private String hostPlusNonHostTargetJson() {
        TargetTriple host = HostPlatform.detect().orElseThrow().target();
        TargetTriple nonHost = nonHostTarget(host);
        return """
                {
                    "windowsX64": %s,
                    "windowsArm64": %s,
                    "linuxX64": %s,
                    "linuxArm64": %s,
                    "macosX64": %s,
                    "macosArm64": %s
                  }""".formatted(
                host == TargetTriple.WINDOWS_X64 || nonHost == TargetTriple.WINDOWS_X64,
                host == TargetTriple.WINDOWS_ARM64 || nonHost == TargetTriple.WINDOWS_ARM64,
                host == TargetTriple.LINUX_X64 || nonHost == TargetTriple.LINUX_X64,
                host == TargetTriple.LINUX_ARM64 || nonHost == TargetTriple.LINUX_ARM64,
                host == TargetTriple.MACOS_X64 || nonHost == TargetTriple.MACOS_X64,
                host == TargetTriple.MACOS_ARM64 || nonHost == TargetTriple.MACOS_ARM64);
    }

    private TargetTriple nonHostTarget(TargetTriple host) {
        for (TargetTriple target : TargetTriple.values()) {
            if (target != host) {
                return target;
            }
        }
        throw new IllegalStateException("no non-host target available");
    }
}
