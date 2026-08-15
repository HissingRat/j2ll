package xyz.melodysky.testsupport.corpus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import xyz.melodysky.report.ReleaseReadinessGate;
import xyz.melodysky.testsupport.AsmFixtureBuilder;
import xyz.melodysky.toolchain.HostPlatform;
import xyz.melodysky.toolchain.TargetTriple;

class ReleaseSuiteRunnerTest implements Opcodes {
    @TempDir
    Path temp;

    @Test
    void releaseSuiteRunsCasesInStableOrderAndWritesManifestSummary() throws Exception {
        ReleaseSuiteResult result = new ReleaseSuiteRunner().run(new ReleaseSuite(
                "release-smoke",
                List.of(minimalCase("z-minimal", false), mixedCase("a-mixed", true))), temp);

        assertEquals("release-smoke", result.name());
        assertEquals(List.of("a-mixed", "z-minimal"), result.cases().stream()
                .map(run -> run.corpusCase().name())
                .toList());
        for (CorpusRunResult run : result.cases()) {
            assertTrue(run.pipelineResult().successful(), run.corpusCase().name());
            assertTrue(run.normalizedOutputMatches(), run.corpusCase().name());
            assertReadinessPassed(run);
        }
        String summary = Files.readString(result.summary());
        assertTrue(summary.contains("\"reportVersion\": 1"));
        assertTrue(summary.contains("\"suiteName\": \"release-smoke\""));
        assertTrue(summary.contains("\"profile\": \"standard\""));
        assertTrue(summary.contains("\"requiredCategories\""));
        assertTrue(summary.contains("\"missingCategories\": []"));
        assertTrue(summary.indexOf("\"name\": \"a-mixed\"") < summary.indexOf("\"name\": \"z-minimal\""));
        assertTrue(summary.contains("\"category\": \"mixed-helper-skipped\""));
        assertTrue(summary.contains("\"nativeLowered\": \"expected\""));
        assertTrue(summary.contains("\"skipped\": \"expected\""));
        assertTrue(summary.contains("\"expectedSupportEvidence\""));
        assertTrue(summary.contains("\"reasonCode\": \"skipped\""));
        assertTrue(summary.contains("\"reportLocation\": \"reports/lowering-report.json\""));
        assertTrue(summary.contains("\"aggregate\""));
        assertTrue(summary.contains("\"totalCases\": 2"));
        assertTrue(summary.contains("\"successCases\": 2"));
        assertTrue(summary.contains("\"strictEvidenceComplete\": true"));
        assertTrue(summary.contains("\"determinismEvidenceComplete\": true"));
    }

    @Test
    void rcProfileSummaryRecordsRequiredAndMissingCategories() throws Exception {
        ReleaseSuiteResult result = new ReleaseSuiteRunner().run(new ReleaseSuite(
                "release-rc",
                ReleaseSuiteProfile.RC,
                List.of(minimalCase("minimal-only", false))), temp);

        String summary = Files.readString(result.summary());
        assertTrue(summary.contains("\"profile\": \"rc\""));
        assertTrue(summary.contains("\"requiredCategories\""));
        assertTrue(summary.contains("\"llvm-native\""));
        assertTrue(summary.contains("\"missingCategories\""));
        assertTrue(summary.contains("\"mixed-helper-skipped\""));
        assertTrue(summary.contains("\"artifact-audit-failure\""));
        assertTrue(summary.contains("\"known-blocker-evidence\""));
    }

    @Test
    void betaProfileRecordsCliDocsAndReportIndexCommandEvidence() throws Exception {
        CorpusCase betaCase = new CorpusCase(
                "beta-command-smoke",
                "cli-artifact-smoke",
                List.of("docs-examples-validated", "report-index", "llvm-native", "mixed-helper-skipped"),
                Map.of("nativeLowered", "expected"),
                "pkg.CorpusMain",
                Map.of(
                        "pkg/CorpusMath.class", AsmFixtureBuilder.classWithAddMethod("pkg/CorpusMath"),
                        "pkg/CorpusMain.class", minimalMainClass()),
                List.of("pkg/CorpusMath#add!(II)I"),
                true,
                "fail",
                null,
                Map.of(),
                true);
        ReleaseSuiteResult result = new ReleaseSuiteRunner().run(new ReleaseSuite(
                "release-beta",
                ReleaseSuiteProfile.BETA,
                List.of(betaCase)), temp);

        CorpusRunResult run = result.cases().get(0);
        assertTrue(run.pipelineResult().successful(), run.pipelineResult().diagnostics().toString());
        assertTrue(run.normalizedOutputMatches(), runMismatch(run));
        String summary = Files.readString(result.summary());
        assertTrue(summary.contains("\"profile\": \"beta\""));
        assertTrue(summary.contains("\"missingCategories\": []"), summary);
        assertTrue(summary.contains("\"category\": \"cli-artifact-smoke\""));
        assertTrue(summary.contains("\"docs-examples-validated\""));
        assertTrue(summary.contains("\"report-index\""));
        assertStrictReadinessPassed(run);
    }

    @Test
    void determinismComparatorMatchesRepeatedMinimalCaseArtifactsAndTokens() throws Exception {
        CorpusCase corpusCase = minimalCase("deterministic-minimal", true);
        CorpusRunner runner = new CorpusRunner();
        CorpusRunResult first = runner.run(corpusCase, temp.resolve("determinism/first"));
        CorpusRunResult second = runner.run(corpusCase, temp.resolve("determinism/second"));

        assertTrue(first.normalizedOutputMatches(), first.outputRun().stderr());
        assertTrue(second.normalizedOutputMatches(), second.outputRun().stderr());
        ReleaseDeterminismComparator.DeterminismEvidence evidence =
                new ReleaseDeterminismComparator().compare(first, second);

        assertTrue(evidence.passed(), evidence.failures().toString());
        assertTrue(evidence.outputJarEntries().stream().anyMatch(entry -> entry.endsWith(".class")));
        assertTrue(evidence.nativeResourcePaths().stream().allMatch(entry -> entry.startsWith("native0/")));
        assertFalse(evidence.embeddedNativeSha256().isEmpty());
        assertTrue(evidence.stableTokens().containsKey("hiddenSymbolName"));
        assertTrue(evidence.stableTokens().containsKey("generatedLoaderPath"));
    }

    @Test
    void releaseSuiteRecordsFailureCaseDiagnosticsWithoutOutputRun() throws Exception {
        ReleaseSuiteResult result = new ReleaseSuiteRunner().run(new ReleaseSuite(
                "release-failure",
                List.of(signedFailCase())), temp);

        CorpusRunResult run = result.cases().get(0);
        assertFalse(run.pipelineResult().successful());
        assertTrue(run.outputRun() == null);
        String summary = Files.readString(result.summary());
        assertTrue(summary.contains("\"expectedPipelineSuccess\": false"));
        assertTrue(summary.contains("\"pipelineSuccessful\": false"));
        assertTrue(summary.contains("\"SIGNED_INPUT_REJECTED\""));
        String packagingReport = Files.readString(run.reportPaths().reports().get("packaging-report.json"));
        assertTrue(packagingReport.contains("\"reasonCode\": \"SIGNED_INPUT_REJECTED\""));
    }

    @Test
    void releaseSuiteRecordsArtifactAuditFailureCaseWithoutFinalArtifact() throws Exception {
        ReleaseSuiteResult result = new ReleaseSuiteRunner().run(new ReleaseSuite(
                "audit-failure-release",
                List.of(artifactAuditFailureCase())), temp);

        CorpusRunResult run = result.cases().get(0);
        assertFalse(run.pipelineResult().successful());
        assertTrue(run.outputRun() == null);
        String summary = Files.readString(result.summary());
        assertTrue(summary.contains("\"category\": \"artifact-audit-failure\""));
        assertTrue(summary.contains("\"expectedFailureStage\": \"ARTIFACT_AUDIT\""));
        assertTrue(summary.contains("\"expectedFailureReasonCode\": \"ARTIFACT_AUDIT_FAILED\""));
        assertTrue(summary.contains("\"finalArtifactWritten\": false"));
        assertTrue(Files.readString(run.reportPaths().reports().get("artifact-audit.json"))
                .contains("FORBIDDEN_PLAINTEXT_JAR_ENTRY"));
    }

    @Test
    void releaseSuiteRecordsConfigFailuresAndWarningSuccessEvidence() throws Exception {
        ReleaseSuiteResult result = new ReleaseSuiteRunner().run(new ReleaseSuite(
                "config-release",
                List.of(
                        CorpusCase.expectedConfigFailure(
                                "missing-schema-version",
                                configJson(false, "[\"pkg/Foo#run!()I\"]"),
                                "MISSING_REQUIRED_FIELD"),
                        CorpusCase.expectedConfigFailure(
                                "invalid-selector-descriptor",
                                configJson(true, "[\"pkg/Foo#run!(not-a-descriptor)\"]"),
                                "INVALID_SELECTOR"),
                        minimalCase("unknown-top-level-warning", false)
                                .withExtraTopLevelConfigFields("\"futureField\": true"))), temp);

        String summary = Files.readString(result.summary());
        assertTrue(summary.contains("\"name\": \"missing-schema-version\""));
        assertTrue(summary.contains("\"expectedFailure\": true"));
        assertTrue(summary.contains("\"expectedFailureStage\": \"CONFIG\""));
        assertTrue(summary.contains("\"expectedFailureReasonCode\": \"MISSING_REQUIRED_FIELD\""));
        assertTrue(summary.contains("\"finalArtifactWritten\": false"));
        assertTrue(summary.contains("\"failure-report.json\""));
        assertTrue(summary.contains("\"name\": \"invalid-selector-descriptor\""));
        assertTrue(summary.contains("\"expectedFailureReasonCode\": \"INVALID_SELECTOR\""));
        assertTrue(summary.contains("\"name\": \"unknown-top-level-warning\""));
        assertTrue(summary.contains("UNKNOWN_FIELD"));

        CorpusRunResult missingSchema = result.cases().stream()
                .filter(run -> run.corpusCase().name().equals("missing-schema-version"))
                .findFirst()
                .orElseThrow();
        assertFalse(missingSchema.pipelineResult().successful());
        assertTrue(missingSchema.originalRun() == null);
        assertTrue(missingSchema.outputRun() == null);
        assertTrue(Files.readString(missingSchema.reportPaths().reports().get("failure-report.json"))
                .contains("\"stage\": \"CONFIG\""));

        CorpusRunResult warningSuccess = result.cases().stream()
                .filter(run -> run.corpusCase().name().equals("unknown-top-level-warning"))
                .findFirst()
                .orElseThrow();
        assertTrue(warningSuccess.pipelineResult().successful(), warningSuccess.pipelineResult().diagnostics().toString());
        assertTrue(warningSuccess.normalizedOutputMatches(), warningSuccess.outputRun().stderr());
    }

    @Test
    void signedStripReleaseCaseProducesRunnableUnsignedOutput() throws Exception {
        ReleaseSuiteResult result = new ReleaseSuiteRunner().run(new ReleaseSuite(
                "signature-release",
                List.of(signedStripCase())), temp);

        CorpusRunResult run = result.cases().get(0);
        assertTrue(run.pipelineResult().successful(), run.pipelineResult().diagnostics().toString());
        assertTrue(run.normalizedOutputMatches(), runMismatch(run));
        try (JarFile jarFile = new JarFile(run.pipelineResult().outputJar().toFile(), false)) {
            assertTrue(jarFile.getJarEntry("META-INF/TEST.SF") == null);
            assertTrue(jarFile.getJarEntry("META-INF/TEST.RSA") == null);
            assertTrue(jarFile.getJarEntry("META-INF/services/pkg.Service") != null);
        }
        String packagingReport = Files.readString(run.reportPaths().reports().get("packaging-report.json"));
        assertTrue(packagingReport.contains("\"action\": \"strip\""));
        assertTrue(packagingReport.contains("\"reasonCode\": \"SIGNATURE_STRIPPED\""));
        assertReadinessPassed(run);
    }

    @Test
    void signedResignReleaseCaseProducesVerifiedRunnableOutput() throws Exception {
        Path keytool = jdkTool("keytool");
        Path jarsigner = jdkTool("jarsigner");
        assumeTrue(Files.isExecutable(keytool), "keytool is unavailable in this JDK");
        assumeTrue(Files.isExecutable(jarsigner), "jarsigner is unavailable in this JDK");

        Path keystore = temp.resolve("release-suite.p12");
        generateKeystore(keytool, keystore);
        ReleaseSuiteResult result = new ReleaseSuiteRunner().run(new ReleaseSuite(
                "signature-release",
                List.of(signedResignCase(keystore))), temp);

        CorpusRunResult run = result.cases().get(0);
        assertTrue(run.pipelineResult().successful(), run.pipelineResult().diagnostics().toString());
        assertTrue(run.normalizedOutputMatches(), runMismatch(run));
        assertEquals(0, verifyJar(jarsigner, run.pipelineResult().outputJar()));
        String packagingReport = Files.readString(run.reportPaths().reports().get("packaging-report.json"));
        assertTrue(packagingReport.contains("\"action\": \"resign\""));
        assertTrue(packagingReport.contains("\"reasonCode\": \"SIGNATURE_RESIGNED\""));
        assertReadinessPassed(run);
    }

    @Test
    void multiReleaseServiceAndModuleInfoReleaseCasePreservesRuntimeBehaviorAndMetadata() throws Exception {
        ReleaseSuiteResult result = new ReleaseSuiteRunner().run(new ReleaseSuite(
                "packaging-release",
                List.of(multiReleaseServiceCase())), temp);

        CorpusRunResult run = result.cases().get(0);
        assertTrue(run.pipelineResult().successful(), run.pipelineResult().diagnostics().toString());
        assertTrue(run.normalizedOutputMatches(), run.outputRun().stderr());
        assertEquals("""
                provided
                v9
                """, run.outputRun().stdout());
        try (JarFile jarFile = new JarFile(run.pipelineResult().outputJar().toFile(), false)) {
            assertTrue(jarFile.getManifest().getMainAttributes().getValue("Multi-Release").equals("true"));
            assertTrue(jarFile.getJarEntry("META-INF/services/pkg.Service") != null);
            assertTrue(jarFile.getJarEntry("module-info.class") != null);
            assertTrue(jarFile.getJarEntry("META-INF/versions/9/pkg/MrValue.class") != null);
        }
        String packagingReport = Files.readString(run.reportPaths().reports().get("packaging-report.json"));
        assertTrue(packagingReport.contains("\"manifestPreserved\": true"));
        assertTrue(packagingReport.contains("\"serviceEntriesPreserved\": 1"));
        assertTrue(packagingReport.contains("\"moduleInfoPreserved\": true"));
        assertTrue(packagingReport.contains("\"multiRelease\": true"));
        assertTrue(packagingReport.contains("\"versionedClassPolicy\": \"baseClassesOnlyPreserveVersionedEntries\""));
        assertReadinessPassed(run);
    }

    @Test
    void reflectionMethodHandleAndLambdaReleaseCasePreservesSkippedMethods() throws Exception {
        ReleaseSuiteResult result = new ReleaseSuiteRunner().run(new ReleaseSuite(
                "dynamic-release",
                List.of(reflectionMethodHandleLambdaCase())), temp);

        CorpusRunResult run = result.cases().get(0);
        assertTrue(run.pipelineResult().successful(), run.pipelineResult().diagnostics().toString());
        assertTrue(run.normalizedOutputMatches(), run.outputRun().stderr());
        assertEquals("""
                9
                value
                reflection-ok
                """, run.outputRun().stdout());
        String loweringReport = Files.readString(run.reportPaths().reports().get("lowering-report.json"));
        String packagingReport = Files.readString(run.reportPaths().reports().get("packaging-report.json"));
        assertTrue(loweringReport.contains("REFLECTION_METHOD_HELPER"));
        assertTrue(loweringReport.contains("METHOD_HANDLE_CHAIN_UNSUPPORTED"));
        assertTrue(loweringReport.contains("LAMBDA_METAFACTORY_HELPER"));
        assertFalse(packagingReport.contains("\"fallbackBlobs\""), packagingReport);
        assertSkippedMethodPreserved(
                run,
                "pkg/MhOps#dynamic!(Ljava/lang/invoke/MethodHandle;)I");
        assertSkippedMethodPreserved(
                run,
                "pkg/LambdaOps#alt!()Ljava/lang/Runnable;");
        assertNoEmbeddedBytecodeSurfaces(run);
        assertReadinessPassed(run);
    }

    @Test
    void jdkUnsupportedReleaseCasePreservesCollectionsOptionalThrowableAndThreadMethods() throws Exception {
        ReleaseSuiteResult result = new ReleaseSuiteRunner().run(new ReleaseSuite(
                "jdk-skipped-release",
                List.of(jdkUnsupportedCase())), temp);

        CorpusRunResult run = result.cases().get(0);
        assertTrue(run.pipelineResult().successful(), run.pipelineResult().diagnostics().toString());
        assertTrue(run.normalizedOutputMatches(), run.outputRun().stderr());
        assertEquals("""
                2:one:true:two:fallback:boom
                thread-done
                """, run.outputRun().stdout());
        String loweringReport = Files.readString(run.reportPaths().reports().get("lowering-report.json"));
        assertTrue(loweringReport.contains("JDK_COLLECTION_HELPER_UNSUPPORTED"), loweringReport);
        assertTrue(loweringReport.contains("THREAD_HELPER_UNSUPPORTED"), loweringReport);
        assertSkippedMethodPreserved(
                run,
                "pkg/JdkReleaseOps#summary!()Ljava/lang/String;");
        assertSkippedMethodPreserved(
                run,
                "pkg/ThreadOps#runThread!(Ljava/lang/Thread;)V");
        assertNoEmbeddedBytecodeSurfaces(run);
        assertReadinessPassed(run);
    }

    @Test
    void realisticUserSamplesRunInStandardSuiteAndProduceRcSummaryEvidence() throws Exception {
        List<CorpusCase> cases = List.of(
                realisticCliAppCase(),
                realisticReflectionCase(),
                realisticPackagingCase());
        ReleaseSuiteResult result = new ReleaseSuiteRunner().run(new ReleaseSuite(
                "realistic-standard",
                cases), temp);

        for (CorpusRunResult run : result.cases()) {
            assertTrue(run.pipelineResult().successful(), run.corpusCase().name() + run.pipelineResult().diagnostics());
            assertTrue(run.normalizedOutputMatches(), run.corpusCase().name() + " " + runMismatch(run));
            assertReadinessPassed(run);
        }
        CorpusRunResult cli = result.cases().stream()
                .filter(run -> run.corpusCase().name().equals("realistic-cli-app"))
                .findFirst()
                .orElseThrow();
        assertEquals("""
                7
                cli2
                2:one:true:two:fallback:boom
                """, cli.outputRun().stdout());
        String summary = Files.readString(result.summary());
        assertTrue(summary.contains("\"category\": \"realistic-cli-app\""));
        assertTrue(summary.contains("\"category\": \"realistic-reflection\""));
        assertTrue(summary.contains("\"category\": \"realistic-packaging\""));

        String rcSummary = new ReleaseSuiteRunner().json(new ReleaseSuite(
                "realistic-rc",
                ReleaseSuiteProfile.RC,
                cases), result.cases());
        assertTrue(rcSummary.contains("\"profile\": \"rc\""));
        assertTrue(rcSummary.contains("\"realistic-cli-app\""));
        assertTrue(rcSummary.contains("\"realistic-reflection\""));
        assertTrue(rcSummary.contains("\"realistic-packaging\""));
    }

    @Test
    void blockerReleaseSuiteCoversKnownRuntimeBoundariesForStrictReadiness() throws Exception {
        ReleaseSuiteResult result = new ReleaseSuiteRunner().run(new ReleaseSuite(
                "blocker-release",
                List.of(
                        reflectionMethodHandleLambdaCase(),
                        safeFinallyCase(),
                        unsafeRawMemoryBoundaryCase(),
                        dynamicVarHandleBoundaryCase(),
                        waitNotifyBoundaryCase())), temp);

        String summary = Files.readString(result.summary());
        for (String reason : List.of(
                "ALT_METAFACTORY_UNSUPPORTED",
                "METHOD_HANDLE_CHAIN_UNSUPPORTED",
                "METHOD_HANDLE_PERMUTE_UNSUPPORTED",
                "METHOD_HANDLE_FILTER_UNSUPPORTED",
                "METHOD_HANDLE_FOLD_UNSUPPORTED",
                "METHOD_HANDLE_COLLECTOR_UNSUPPORTED",
                "UNSAFE_RAW_MEMORY_UNSUPPORTED",
                "VAR_HANDLE_DYNAMIC_UNSUPPORTED",
                "WAIT_NOTIFY_UNSUPPORTED")) {
            assertTrue(summary.contains(reason), reason);
        }
        assertTrue(summary.contains("\"reportLocation\": \"reports/skipped-method-report.json\""));
        assertFalse(summary.contains("frontend-skip-report.json"), summary);
        assertTrue(summary.contains("\"name\": \"safe-finally-cleanup\""));
        for (CorpusRunResult run : result.cases()) {
            assertTrue(run.pipelineResult().successful() == run.corpusCase().expectedPipelineSuccess(), run.corpusCase().name());
            if (run.corpusCase().expectedPipelineSuccess()) {
                assertTrue(run.normalizedOutputMatches(), run.corpusCase().name());
            }
        }
        assertStrictReadinessPassed(result.cases().get(0));
    }

    @Test
    void recordsInjectedRequiredCrossTargetBuildFailureAfterSupportedPreflight() throws Exception {
        ReleaseSuiteResult result = new ReleaseSuiteRunner().run(new ReleaseSuite(
                "required-target-failure",
                List.of(requiredCrossTargetBuildFailureCase())), temp);

        CorpusRunResult run = result.cases().get(0);
        assertFalse(run.pipelineResult().successful());
        assertTrue(run.outputRun() == null);
        Path workspace = run.reportPaths().reports().values().iterator().next().getParent().getParent();
        String summary = Files.readString(result.summary());
        String packaging = Files.readString(run.reportPaths().reports().get("packaging-report.json"));
        String manifest = Files.readString(workspace.resolve("native/zig-workspace/j2ll-build-manifest.json"));
        assertTrue(summary.contains("\"name\": \"cross-target-build-failure\""), summary);
        assertTrue(summary.contains("\"expectedFailureReasonCode\": \"ZIG_TARGET_UNBUILDABLE\""), summary);
        assertTrue(Files.readString(run.reportPaths().reports().get("diagnostics.json"))
                .contains("ZIG_TARGET_UNBUILDABLE"));
        assertTrue(packaging.contains("\"failureKind\": \"zigBuildFailed\""), packaging);
        assertTrue(packaging.contains("\"requiredCapability\": \"managedZig0.15.2CrossTargetSharedLibrary\""), packaging);
        assertTrue(manifest.contains("\"reasonCode\": \"ZIG_CROSS_TARGET_SUPPORTED\""), manifest);
        assertTrue(manifest.contains("\"failedTargets\": []"), manifest);
        assertFalse(Files.exists(workspace.resolve(run.inputJar().getFileName())));
    }

    private void assertReadinessPassed(CorpusRunResult run) throws Exception {
        assertRequiredSuccessReports(run);
        Path workspace = run.reportPaths().reports().values().iterator().next().getParent().getParent();
        var result = new ReleaseReadinessGate().evaluate(workspace);
        assertTrue(result.passed(), result.checks().toString());
    }

    private void assertStrictReadinessPassed(CorpusRunResult run) throws Exception {
        assertRequiredSuccessReports(run);
        Path workspace = run.reportPaths().reports().values().iterator().next().getParent().getParent();
        var result = new ReleaseReadinessGate().evaluate(workspace, true);
        assertTrue(result.passed(), result.checks().toString());
    }

    private void assertRequiredSuccessReports(CorpusRunResult run) {
        assertEquals(List.of(
                "artifact-audit.json",
                "diagnostics.json",
                "index.json",
                "known-blockers.json",
                "lowering-report.json",
                "opcode-support-matrix.json",
                "packaging-report.json",
                "protection-report.json",
                "release-readiness.json",
                "skipped-method-report.json",
                "summary.json",
                "summary.md",
                "support-matrix.json",
                "symbol-audit.json"), run.reportPaths().reports().keySet().stream().toList());
        run.reportPaths().reports().forEach((name, path) -> assertTrue(Files.isRegularFile(path), name));
    }

    private CorpusCase minimalCase(String name, boolean protection) {
        return new CorpusCase(
                name,
                "llvm-native",
                List.of("static-int", "llvm-native"),
                Map.of("nativeLowered", "expected"),
                "pkg.CorpusMain",
                Map.of(
                        "pkg/CorpusMath.class", AsmFixtureBuilder.classWithAddMethod("pkg/CorpusMath"),
                        "pkg/CorpusMain.class", minimalMainClass()),
                List.of("pkg/CorpusMath#add!(II)I"),
                protection,
                "fail",
                null,
                Map.of(),
                true);
    }

    private CorpusCase mixedCase(String name, boolean protection) {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("pkg/CorpusMath.class", AsmFixtureBuilder.classWithAddMethod("pkg/CorpusMath"));
        entries.put("pkg/StringBuilderOps.class", AsmFixtureBuilder.classWithJdkStringBuilderMethods("pkg/StringBuilderOps"));
        entries.put("pkg/JdkUnsupported.class", AsmFixtureBuilder.classWithUnsupportedJdkStringCall("pkg/JdkUnsupported"));
        entries.put("pkg/MixedCorpusMain.class", mixedMainClass());
        return new CorpusCase(
                name,
                "mixed-helper-skipped",
                List.of("llvm-native", "string-builder-helper", "skipped-method-preservation"),
                Map.of("nativeLowered", "expected", "skipped", "expected"),
                "pkg.MixedCorpusMain",
                entries,
                List.of(
                        "pkg/CorpusMath#add!(II)I",
                        "pkg/StringBuilderOps#build!(Ljava/lang/String;I)Ljava/lang/String;",
                        "pkg/JdkUnsupported#substring!(Ljava/lang/String;)Ljava/lang/String;"),
                protection,
                "fail",
                null,
                Map.of(),
                true);
    }

    private CorpusCase signedFailCase() {
        return new CorpusCase(
                "signed-fail",
                "signature",
                List.of("signed-input", "fail-policy"),
                Map.of("SIGNED_INPUT_REJECTED", "expected"),
                "pkg.ServiceMain",
                serviceJarEntries(true),
                List.of("pkg/Provider#name!()Ljava/lang/String;"),
                false,
                "fail",
                null,
                Map.of(),
                false);
    }

    private CorpusCase artifactAuditFailureCase() {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("pkg/LeakyBox.class", leakyBoxClass());
        entries.put("pkg/LeakyMain.class", leakyMainClass());
        entries.put("leak.txt", "release-template-leak".getBytes(StandardCharsets.UTF_8));
        return new CorpusCase(
                "artifact-audit-failure",
                "artifact-audit-failure",
                List.of("template-stable-surface", "artifact-audit-failure"),
                Map.of("ARTIFACT_AUDIT_FAILED", "expected"),
                "pkg.LeakyMain",
                entries,
                List.of("pkg/LeakyBox#<init>!()V"),
                true,
                "fail",
                null,
                Map.of(),
                false)
                .withExpectedFailure("ARTIFACT_AUDIT", "ARTIFACT_AUDIT_FAILED");
    }

    private CorpusCase signedStripCase() {
        return new CorpusCase(
                "signed-strip",
                "signature",
                List.of("signed-input", "strip-policy"),
                Map.of("SIGNATURE_STRIPPED", "expected"),
                "pkg.ServiceMain",
                serviceJarEntries(true),
                List.of("pkg/Provider#name!()Ljava/lang/String;"),
                false,
                "strip",
                null,
                Map.of(),
                true);
    }

    private CorpusCase signedResignCase(Path keystore) {
        return new CorpusCase(
                "signed-resign",
                "signature",
                List.of("signed-input", "resign-policy", "jarsigner-verify"),
                Map.of("SIGNATURE_RESIGNED", "expected"),
                "pkg.ServiceMain",
                serviceJarEntries(true),
                List.of("pkg/Provider#name!()Ljava/lang/String;"),
                false,
                "resign",
                """
                        {
                          "keystorePath": "%s",
                          "storePasswordEnv": "J2LL_RELEASE_SUITE_STORE_PASS",
                          "keyAlias": "j2ll",
                          "keyPasswordEnv": "J2LL_RELEASE_SUITE_KEY_PASS",
                          "tsaUrl": null
                        }
                        """.formatted(keystore.toAbsolutePath().toString().replace("\\", "\\\\")),
                Map.of(
                        "J2LL_RELEASE_SUITE_STORE_PASS", "changeit",
                        "J2LL_RELEASE_SUITE_KEY_PASS", "changeit"),
                true);
    }

    private CorpusCase multiReleaseServiceCase() {
        return new CorpusCase(
                "multi-release-service-module",
                "packaging-preservation",
                List.of("service-loader", "multi-release", "module-info"),
                Map.of("baseClassesOnlyPreserveVersionedEntries", "expected"),
                "pkg.MultiReleaseServiceMain",
                multiReleaseServiceJarEntries(),
                List.of(
                        "pkg/Provider#name!()Ljava/lang/String;",
                        "pkg/MrValue#value!()Ljava/lang/String;"),
                false,
                "fail",
                null,
                Map.of(),
                true);
    }

    private CorpusCase reflectionMethodHandleLambdaCase() {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("pkg/ReflectionTarget.class", AsmFixtureBuilder.classWithReflectionTarget("pkg/ReflectionTarget"));
        entries.put("pkg/ReflectionOps.class",
                AsmFixtureBuilder.classWithStaticReflectionMethods("pkg/ReflectionOps", "pkg/ReflectionTarget"));
        entries.put("pkg/MhOps.class", AsmFixtureBuilder.classWithMethodHandleInvokeExact("pkg/MhOps"));
        entries.put("pkg/LambdaOps.class", AsmFixtureBuilder.classWithLambdaMetafactoryMethods("pkg/LambdaOps"));
        entries.put("pkg/DynamicMain.class", dynamicMainClass());
        return new CorpusCase(
                "reflection-methodhandle-lambda",
                "dynamic-runtime",
                List.of("reflection", "method-handle", "lambda", "skipped-method-preservation"),
                Map.of(
                        "REFLECTION_METHOD_HELPER", "expected",
                        "LAMBDA_METAFACTORY_HELPER", "expected",
                        "ALT_METAFACTORY_UNSUPPORTED", "expected",
                        "METHOD_HANDLE_CHAIN_UNSUPPORTED", "expected",
                        "METHOD_HANDLE_PERMUTE_UNSUPPORTED", "expected",
                        "METHOD_HANDLE_FILTER_UNSUPPORTED", "expected",
                        "METHOD_HANDLE_FOLD_UNSUPPORTED", "expected",
                        "METHOD_HANDLE_COLLECTOR_UNSUPPORTED", "expected"),
                "pkg.DynamicMain",
                entries,
                List.of(
                        "pkg/ReflectionOps#declaredMethod!()V",
                        "pkg/ReflectionOps#reflectiveInvoke!()V",
                        "pkg/ReflectionOps#dynamicForName!(Ljava/lang/String;)V",
                        "pkg/MhOps#direct!()I",
                        "pkg/MhOps#dynamic!(Ljava/lang/invoke/MethodHandle;)I",
                        "pkg/LambdaOps#staticReference!()Ljava/util/function/Supplier;",
                        "pkg/LambdaOps#alt!()Ljava/lang/Runnable;"),
                true,
                "fail",
                null,
                Map.of(),
                true);
    }

    private CorpusCase jdkUnsupportedCase() {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("pkg/JdkReleaseOps.class", jdkReleaseOpsClass());
        entries.put("pkg/ThreadOps.class", AsmFixtureBuilder.classWithThreadStartJoinMethod("pkg/ThreadOps"));
        entries.put("pkg/NoopRunnable.class", noopRunnableClass());
        entries.put("pkg/JdkUnsupportedMain.class", jdkUnsupportedMainClass());
        return new CorpusCase(
                "jdk-skipped",
                "jdk-skipped",
                List.of("arraylist", "hashmap", "optional", "throwable", "thread", "skipped-method-preservation"),
                Map.of("skipped", "expected"),
                "pkg.JdkUnsupportedMain",
                entries,
                List.of(
                        "pkg/JdkReleaseOps#summary!()Ljava/lang/String;",
                        "pkg/ThreadOps#runThread!(Ljava/lang/Thread;)V"),
                true,
                "fail",
                null,
                Map.of(),
                true);
    }

    private CorpusCase realisticCliAppCase() {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/MANIFEST.MF", """
                Manifest-Version: 1.0\r
                Main-Class: pkg.RealisticCliMain\r
                \r
                """.getBytes(StandardCharsets.UTF_8));
        entries.put("pkg/CorpusMath.class", AsmFixtureBuilder.classWithAddMethod("pkg/CorpusMath"));
        entries.put("pkg/StringBuilderOps.class", AsmFixtureBuilder.classWithJdkStringBuilderMethods("pkg/StringBuilderOps"));
        entries.put("pkg/JdkReleaseOps.class", jdkReleaseOpsClass());
        entries.put("pkg/RealisticCliMain.class", realisticCliMainClass());
        return new CorpusCase(
                "realistic-cli-app",
                "realistic-cli-app",
                List.of("main-class", "args-parsing", "string-builder", "jdk-skipped", "protection-all-on"),
                Map.of(
                        "nativeLowered", "expected",
                        "skipped", "expected"),
                "pkg.RealisticCliMain",
                entries,
                List.of(
                        "pkg/CorpusMath#add!(II)I",
                        "pkg/StringBuilderOps#build!(Ljava/lang/String;I)Ljava/lang/String;",
                        "pkg/JdkReleaseOps#summary!()Ljava/lang/String;"),
                true,
                "fail",
                null,
                Map.of(),
                true);
    }

    private CorpusCase realisticReflectionCase() {
        CorpusCase base = reflectionMethodHandleLambdaCase();
        return new CorpusCase(
                "realistic-reflection",
                "realistic-reflection",
                List.of("private-reflection", "dynamic-reflection-skipped", "method-handle-skipped"),
                base.expectedSupportStatuses(),
                base.mainClass(),
                base.jarEntries(),
                base.selectors(),
                true,
                base.signaturePolicy(),
                base.signingJson(),
                base.environment(),
                true);
    }

    private CorpusCase realisticPackagingCase() {
        CorpusCase base = multiReleaseServiceCase();
        return new CorpusCase(
                "realistic-packaging",
                "realistic-packaging",
                List.of("services", "module-info", "multi-release", "packaging-preservation"),
                base.expectedSupportStatuses(),
                base.mainClass(),
                base.jarEntries(),
                base.selectors(),
                false,
                base.signaturePolicy(),
                base.signingJson(),
                base.environment(),
                true);
    }

    private CorpusCase unsafeRawMemoryBoundaryCase() {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("pkg/UnsafeOps.class", AsmFixtureBuilder.classWithUnsafeMethods("pkg/UnsafeOps"));
        entries.put("pkg/UnsafeBoundaryMain.class", literalMainClass("pkg/UnsafeBoundaryMain", "unsafe-boundary"));
        return new CorpusCase(
                "unsafe-raw-memory",
                "unsafe-boundary",
                List.of("unsafe", "raw-memory", "skipped-method-preservation"),
                Map.of("UNSAFE_RAW_MEMORY_UNSUPPORTED", "expected"),
                "pkg.UnsafeBoundaryMain",
                entries,
                List.of(
                        "pkg/UnsafeOps#allocateMemory!(Lsun/misc/Unsafe;J)J",
                        "pkg/UnsafeOps#copyMemory!(Lsun/misc/Unsafe;JJJ)V",
                        "pkg/UnsafeOps#freeMemory!(Lsun/misc/Unsafe;J)V",
                        "pkg/UnsafeOps#getRawLong!(Lsun/misc/Unsafe;J)J",
                        "pkg/UnsafeOps#park!(Lsun/misc/Unsafe;ZJ)V",
                        "pkg/UnsafeOps#putRawLong!(Lsun/misc/Unsafe;JJ)V",
                        "pkg/UnsafeOps#reallocateMemory!(Lsun/misc/Unsafe;JJ)J",
                        "pkg/UnsafeOps#unpark!(Lsun/misc/Unsafe;Ljava/lang/Object;)V",
                        "pkg/UnsafeOps#unsupported!(Lsun/misc/Unsafe;J)B"),
                true,
                "fail",
                null,
                Map.of(),
                true);
    }

    private CorpusCase dynamicVarHandleBoundaryCase() {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("pkg/VarHandleOps.class", AsmFixtureBuilder.classWithVarHandleMethods("pkg/VarHandleOps"));
        entries.put("pkg/VarHandleBoundaryMain.class", literalMainClass("pkg/VarHandleBoundaryMain", "varhandle-boundary"));
        return new CorpusCase(
                "dynamic-varhandle-boundary",
                "varhandle-boundary",
                List.of("varhandle", "dynamic-shape", "skipped-method-preservation"),
                Map.of("VAR_HANDLE_DYNAMIC_UNSUPPORTED", "expected"),
                "pkg.VarHandleBoundaryMain",
                entries,
                List.of(
                        "pkg/VarHandleOps#get!(Ljava/lang/invoke/VarHandle;Ljava/lang/Object;)Ljava/lang/Object;",
                        "pkg/VarHandleOps#set!(Ljava/lang/invoke/VarHandle;Ljava/lang/Object;Ljava/lang/Object;)V",
                        "pkg/VarHandleOps#compareAndSet!(Ljava/lang/invoke/VarHandle;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z"),
                true,
                "fail",
                null,
                Map.of(),
                true);
    }

    private CorpusCase safeFinallyCase() {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("pkg/SafeFinally.class", AsmFixtureBuilder.classWithCatchAllFinallyShape("pkg/SafeFinally"));
        entries.put("pkg/SafeFinallyMain.class", safeFinallyMainClass());
        return new CorpusCase(
                "safe-finally-cleanup",
                "exception-finally",
                List.of("safe-finally", "single-exit-cleanup"),
                Map.of("nativeLowered", "expected"),
                "pkg.SafeFinallyMain",
                entries,
                List.of("pkg/SafeFinally#cleanup!()V"),
                true,
                "fail",
                null,
                Map.of(),
                true);
    }

    private CorpusCase waitNotifyBoundaryCase() {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("pkg/WaitNotifyOps.class", AsmFixtureBuilder.classWithWaitNotifyMethod("pkg/WaitNotifyOps"));
        entries.put("pkg/WaitNotifyBoundaryMain.class", waitNotifyBoundaryMainClass());
        return new CorpusCase(
                "wait-notify-boundary",
                "thread-monitor-boundary",
                List.of("wait-notify", "skipped-method-preservation"),
                Map.of("WAIT_NOTIFY_UNSUPPORTED", "expected"),
                "pkg.WaitNotifyBoundaryMain",
                entries,
                List.of("pkg/WaitNotifyOps#waitNotify!(Ljava/lang/Object;)V"),
                true,
                "fail",
                null,
                Map.of(),
                true);
    }

    private CorpusCase requiredCrossTargetBuildFailureCase() {
        return new CorpusCase(
                "cross-target-build-failure",
                "required-target-failure",
                List.of("cross-target-matrix", "managed-zig", "test-driver-build-failure"),
                Map.of("ZIG_TARGET_UNBUILDABLE", "expected"),
                "pkg.CorpusMain",
                Map.of(
                        "pkg/CorpusMath.class", AsmFixtureBuilder.classWithAddMethod("pkg/CorpusMath"),
                        "pkg/CorpusMain.class", minimalMainClass()),
                List.of("pkg/CorpusMath#add!(II)I"),
                false,
                "fail",
                null,
                Map.of(),
                false,
                hostPlusCrossTargetJson())
                .withExpectedFailure("TOOLCHAIN", "ZIG_TARGET_UNBUILDABLE");
    }

    private String configJson(boolean includeSchemaVersion, String selectorsJson) {
        String schema = includeSchemaVersion ? "\"schemaVersion\": 1,\n" : "";
        return """
                {
                  %s
                  "jarFile": "${INPUT_JAR}",
                  "classPath": [],
                  "javaHome": null,
                  "runtimeImage": null,
                  "worldModel": "PARTIAL_WORLD",
                  "outputDirectory": "out",
                  "whiteList": %s,
                  "blackList": [],
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
                    "enabled": false,
                    "seed": "corpus-seed",
                    "ir": {
                      "enabled": false,
                      "controlFlowFlattening": false,
                      "fakeBranches": false,
                      "basicBlockSplitting": false,
                      "constantEncryption": false,
                      "stringEncryption": false,
                      "methodInlining": false,
                      "methodSplitting": false,
                      "callIndirection": false,
                      "fieldInternalization": false,
                      "methodInternalization": false,
                      "publicMethodInternalizationAllowList": [],
                      "methodTableHiding": false,
                      "blockNameObfuscation": false
                    },
                    "llvm": {
                      "enabled": false,
                      "nameObfuscation": false,
                      "opaquePredicates": false,
                      "blockLayoutPerturbation": false,
                      "indirectCalls": false,
                      "globalLayout": false
                    },
                    "binary": {
                      "enabled": false,
                      "hideInternalSymbols": true,
                      "strip": true,
                      "removePdb": true,
                      "symbolAudit": true,
                      "retainUnwindInfo": false
                    }
                  }
                }
                """.formatted(schema, selectorsJson);
    }

    private byte[] minimalMainClass() {
        ClassWriter writer = mainClass("pkg/CorpusMain");
        MethodVisitor main = beginMain(writer);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitInsn(ICONST_2);
        main.visitInsn(ICONST_5);
        main.visitMethodInsn(INVOKESTATIC, "pkg/CorpusMath", "add", "(II)I", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] mixedMainClass() {
        ClassWriter writer = mainClass("pkg/MixedCorpusMain");
        MethodVisitor main = beginMain(writer);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitInsn(ICONST_2);
        main.visitInsn(ICONST_5);
        main.visitMethodInsn(INVOKESTATIC, "pkg/CorpusMath", "add", "(II)I", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("v");
        main.visitInsn(ICONST_3);
        main.visitMethodInsn(INVOKESTATIC, "pkg/StringBuilderOps", "build", "(Ljava/lang/String;I)Ljava/lang/String;", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("abc");
        main.visitMethodInsn(INVOKESTATIC, "pkg/JdkUnsupported", "substring", "(Ljava/lang/String;)Ljava/lang/String;", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] dynamicMainClass() {
        ClassWriter writer = mainClass("pkg/DynamicMain");
        MethodVisitor main = beginMain(writer);
        main.visitMethodInsn(INVOKESTATIC, "pkg/ReflectionOps", "declaredMethod", "()V", false);
        main.visitMethodInsn(INVOKESTATIC, "pkg/ReflectionOps", "reflectiveInvoke", "()V", false);
        main.visitLdcInsn("pkg.ReflectionTarget");
        main.visitMethodInsn(INVOKESTATIC, "pkg/ReflectionOps", "dynamicForName", "(Ljava/lang/String;)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitMethodInsn(INVOKESTATIC, "pkg/MhOps", "direct", "()I", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitMethodInsn(INVOKESTATIC, "pkg/LambdaOps", "staticReference", "()Ljava/util/function/Supplier;", false);
        main.visitMethodInsn(INVOKEINTERFACE, "java/util/function/Supplier", "get", "()Ljava/lang/Object;", true);
        main.visitTypeInsn(CHECKCAST, "java/lang/String");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitMethodInsn(INVOKESTATIC, "pkg/LambdaOps", "alt", "()Ljava/lang/Runnable;", false);
        main.visitMethodInsn(INVOKEINTERFACE, "java/lang/Runnable", "run", "()V", true);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("reflection-ok");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] jdkUnsupportedMainClass() {
        ClassWriter writer = mainClass("pkg/JdkUnsupportedMain");
        MethodVisitor main = beginMainThrows(writer, "java/lang/InterruptedException");
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitMethodInsn(INVOKESTATIC, "pkg/JdkReleaseOps", "summary", "()Ljava/lang/String;", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitTypeInsn(NEW, "java/lang/Thread");
        main.visitInsn(DUP);
        main.visitTypeInsn(NEW, "pkg/NoopRunnable");
        main.visitInsn(DUP);
        main.visitMethodInsn(INVOKESPECIAL, "pkg/NoopRunnable", "<init>", "()V", false);
        main.visitMethodInsn(INVOKESPECIAL, "java/lang/Thread", "<init>", "(Ljava/lang/Runnable;)V", false);
        main.visitMethodInsn(INVOKESTATIC, "pkg/ThreadOps", "runThread", "(Ljava/lang/Thread;)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("thread-done");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] realisticCliMainClass() {
        ClassWriter writer = mainClass("pkg/RealisticCliMain");
        MethodVisitor main = beginMain(writer);
        main.visitVarInsn(ALOAD, 0);
        main.visitInsn(ARRAYLENGTH);
        org.objectweb.asm.Label noArgs = new org.objectweb.asm.Label();
        org.objectweb.asm.Label afterArg = new org.objectweb.asm.Label();
        main.visitJumpInsn(IFEQ, noArgs);
        main.visitVarInsn(ALOAD, 0);
        main.visitInsn(ICONST_0);
        main.visitInsn(AALOAD);
        main.visitVarInsn(ASTORE, 1);
        main.visitJumpInsn(GOTO, afterArg);
        main.visitLabel(noArgs);
        main.visitLdcInsn("cli");
        main.visitVarInsn(ASTORE, 1);
        main.visitLabel(afterArg);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitInsn(ICONST_2);
        main.visitInsn(ICONST_5);
        main.visitMethodInsn(INVOKESTATIC, "pkg/CorpusMath", "add", "(II)I", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitVarInsn(ALOAD, 1);
        main.visitInsn(ICONST_2);
        main.visitMethodInsn(INVOKESTATIC, "pkg/StringBuilderOps", "build", "(Ljava/lang/String;I)Ljava/lang/String;", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitMethodInsn(INVOKESTATIC, "pkg/JdkReleaseOps", "summary", "()Ljava/lang/String;", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] literalMainClass(String internalName, String line) {
        ClassWriter writer = mainClass(internalName);
        MethodVisitor main = beginMain(writer);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn(line);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] leakyMainClass() {
        ClassWriter writer = mainClass("pkg/LeakyMain");
        MethodVisitor main = beginMain(writer);
        main.visitTypeInsn(NEW, "pkg/LeakyBox");
        main.visitInsn(DUP);
        main.visitMethodInsn(INVOKESPECIAL, "pkg/LeakyBox", "<init>", "()V", false);
        main.visitInsn(POP);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("audit-source-ok");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] leakyBoxClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/LeakyBox", null, "java/lang/Object", null);
        writer.visitField(ACC_PRIVATE, "value", "Ljava/lang/String;", null, null).visitEnd();
        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitLdcInsn("release-template-leak");
        constructor.visitFieldInsn(PUTFIELD, "pkg/LeakyBox", "value", "Ljava/lang/String;");
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] waitNotifyBoundaryMainClass() {
        ClassWriter writer = mainClass("pkg/WaitNotifyBoundaryMain");
        MethodVisitor main = beginMainThrows(writer, "java/lang/InterruptedException");
        org.objectweb.asm.Label start = new org.objectweb.asm.Label();
        org.objectweb.asm.Label end = new org.objectweb.asm.Label();
        org.objectweb.asm.Label handler = new org.objectweb.asm.Label();
        org.objectweb.asm.Label done = new org.objectweb.asm.Label();
        main.visitTryCatchBlock(start, end, handler, "java/lang/IllegalMonitorStateException");
        main.visitLabel(start);
        main.visitTypeInsn(NEW, "java/lang/Object");
        main.visitInsn(DUP);
        main.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        main.visitMethodInsn(INVOKESTATIC, "pkg/WaitNotifyOps", "waitNotify", "(Ljava/lang/Object;)V", false);
        main.visitLabel(end);
        main.visitJumpInsn(GOTO, done);
        main.visitLabel(handler);
        main.visitVarInsn(ASTORE, 1);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("wait-boundary");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        main.visitLabel(done);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] safeFinallyMainClass() {
        ClassWriter writer = mainClass("pkg/SafeFinallyMain");
        MethodVisitor main = beginMain(writer);
        main.visitMethodInsn(INVOKESTATIC, "pkg/SafeFinally", "cleanup", "()V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("finally-ok");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] noopRunnableClass() {
        ClassWriter writer = mainClass("pkg/NoopRunnable", "java/lang/Object", new String[] {"java/lang/Runnable"});
        MethodVisitor run = writer.visitMethod(ACC_PUBLIC, "run", "()V", null, null);
        run.visitCode();
        run.visitInsn(RETURN);
        run.visitMaxs(0, 0);
        run.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] jdkReleaseOpsClass() {
        ClassWriter writer = mainClass("pkg/JdkReleaseOps");
        MethodVisitor summary = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "summary", "()Ljava/lang/String;", null, null);
        summary.visitCode();
        summary.visitTypeInsn(NEW, "java/util/ArrayList");
        summary.visitInsn(DUP);
        summary.visitMethodInsn(INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
        summary.visitVarInsn(ASTORE, 0);
        summary.visitVarInsn(ALOAD, 0);
        summary.visitLdcInsn("one");
        summary.visitMethodInsn(INVOKEVIRTUAL, "java/util/ArrayList", "add", "(Ljava/lang/Object;)Z", false);
        summary.visitInsn(POP);
        summary.visitTypeInsn(NEW, "java/util/HashMap");
        summary.visitInsn(DUP);
        summary.visitMethodInsn(INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false);
        summary.visitVarInsn(ASTORE, 1);
        summary.visitVarInsn(ALOAD, 1);
        summary.visitLdcInsn("k");
        summary.visitLdcInsn("two");
        summary.visitMethodInsn(INVOKEVIRTUAL, "java/util/HashMap", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
        summary.visitInsn(POP);
        summary.visitInsn(ACONST_NULL);
        summary.visitMethodInsn(INVOKESTATIC, "java/util/Optional", "ofNullable", "(Ljava/lang/Object;)Ljava/util/Optional;", false);
        summary.visitVarInsn(ASTORE, 2);
        summary.visitTypeInsn(NEW, "java/lang/RuntimeException");
        summary.visitInsn(DUP);
        summary.visitLdcInsn("boom");
        summary.visitMethodInsn(INVOKESPECIAL, "java/lang/RuntimeException", "<init>", "(Ljava/lang/String;)V", false);
        summary.visitVarInsn(ASTORE, 3);
        summary.visitTypeInsn(NEW, "java/lang/StringBuilder");
        summary.visitInsn(DUP);
        summary.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
        summary.visitVarInsn(ALOAD, 0);
        summary.visitMethodInsn(INVOKEVIRTUAL, "java/util/ArrayList", "size", "()I", false);
        summary.visitInsn(ICONST_1);
        summary.visitInsn(IADD);
        summary.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(I)Ljava/lang/StringBuilder;", false);
        appendLiteral(summary, ":");
        summary.visitVarInsn(ALOAD, 0);
        summary.visitInsn(ICONST_0);
        summary.visitMethodInsn(INVOKEVIRTUAL, "java/util/ArrayList", "get", "(I)Ljava/lang/Object;", false);
        summary.visitTypeInsn(CHECKCAST, "java/lang/String");
        summary.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        appendLiteral(summary, ":");
        summary.visitVarInsn(ALOAD, 1);
        summary.visitLdcInsn("k");
        summary.visitMethodInsn(INVOKEVIRTUAL, "java/util/HashMap", "containsKey", "(Ljava/lang/Object;)Z", false);
        summary.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Z)Ljava/lang/StringBuilder;", false);
        appendLiteral(summary, ":");
        summary.visitVarInsn(ALOAD, 1);
        summary.visitLdcInsn("k");
        summary.visitMethodInsn(INVOKEVIRTUAL, "java/util/HashMap", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        summary.visitTypeInsn(CHECKCAST, "java/lang/String");
        summary.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        appendLiteral(summary, ":");
        summary.visitVarInsn(ALOAD, 2);
        summary.visitLdcInsn("fallback");
        summary.visitMethodInsn(INVOKEVIRTUAL, "java/util/Optional", "orElse", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        summary.visitTypeInsn(CHECKCAST, "java/lang/String");
        summary.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        appendLiteral(summary, ":");
        summary.visitVarInsn(ALOAD, 3);
        summary.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Throwable", "getMessage", "()Ljava/lang/String;", false);
        summary.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        summary.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        summary.visitInsn(ARETURN);
        summary.visitMaxs(0, 0);
        summary.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private void appendLiteral(MethodVisitor method, String value) {
        method.visitLdcInsn(value);
        method.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
    }

    private Map<String, byte[]> serviceJarEntries(boolean signed) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/MANIFEST.MF", """
                Manifest-Version: 1.0\r
                Main-Class: pkg.ServiceMain\r
                Multi-Release: true\r
                \r
                """.getBytes(StandardCharsets.UTF_8));
        entries.put("pkg/Service.class", serviceInterfaceClass());
        entries.put("pkg/Provider.class", serviceProviderClass());
        entries.put("pkg/ServiceMain.class", serviceMainClass());
        entries.put("META-INF/services/pkg.Service", "pkg.Provider\n".getBytes(StandardCharsets.UTF_8));
        entries.put("module-info.class", moduleInfoClass());
        entries.put("META-INF/versions/9/resource.txt", "v9\n".getBytes(StandardCharsets.UTF_8));
        if (signed) {
            entries.put("META-INF/TEST.SF", "Signature-Version: 1.0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            entries.put("META-INF/TEST.RSA", new byte[] {1, 2, 3, 4});
        }
        return entries;
    }

    private Map<String, byte[]> multiReleaseServiceJarEntries() {
        Map<String, byte[]> entries = new LinkedHashMap<>(serviceJarEntries(false));
        entries.put("META-INF/MANIFEST.MF", """
                Manifest-Version: 1.0\r
                Main-Class: pkg.MultiReleaseServiceMain\r
                Multi-Release: true\r
                \r
                """.getBytes(StandardCharsets.UTF_8));
        entries.remove("META-INF/versions/9/resource.txt");
        entries.put("pkg/MrValue.class", mrValueClass("base"));
        entries.put("META-INF/versions/9/pkg/MrValue.class", mrValueClass("v9"));
        entries.put("pkg/MultiReleaseServiceMain.class", multiReleaseServiceMainClass());
        return entries;
    }

    private byte[] serviceInterfaceClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, "pkg/Service", null, "java/lang/Object", null);
        writer.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "name", "()Ljava/lang/String;", null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] serviceProviderClass() {
        ClassWriter writer = mainClass("pkg/Provider", "java/lang/Object", new String[] {"pkg/Service"});
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
        ClassWriter writer = mainClass("pkg/ServiceMain");
        MethodVisitor main = beginMain(writer);
        printFirstService(main);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] mrValueClass(String value) {
        ClassWriter writer = mainClass("pkg/MrValue");
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
        ClassWriter writer = mainClass("pkg/MultiReleaseServiceMain");
        MethodVisitor main = beginMain(writer);
        printFirstService(main);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitMethodInsn(INVOKESTATIC, "pkg/MrValue", "value", "()Ljava/lang/String;", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private void printFirstService(MethodVisitor main) {
        main.visitLdcInsn(org.objectweb.asm.Type.getObjectType("pkg/Service"));
        main.visitMethodInsn(INVOKESTATIC, "java/util/ServiceLoader", "load", "(Ljava/lang/Class;)Ljava/util/ServiceLoader;", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/util/ServiceLoader", "iterator", "()Ljava/util/Iterator;", false);
        main.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
        main.visitTypeInsn(CHECKCAST, "pkg/Service");
        main.visitMethodInsn(INVOKEINTERFACE, "pkg/Service", "name", "()Ljava/lang/String;", true);
        main.visitVarInsn(ASTORE, 1);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitVarInsn(ALOAD, 1);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
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

    private ClassWriter mainClass(String internalName) {
        return mainClass(internalName, "java/lang/Object", null);
    }

    private ClassWriter mainClass(String internalName, String superName, String[] interfaces) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, superName, interfaces);
        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, superName, "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        return writer;
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
                        "-dname", "CN=j2ll release suite, OU=tests, O=melodysky, L=test, ST=test, C=US",
                        "-noprompt")
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
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

    private String hostPlusCrossTargetJson() {
        TargetTriple host = HostPlatform.detect().orElseThrow().target();
        TargetTriple crossTarget = crossTarget(host);
        return """
                {
                  "windowsX64": %s,
                  "windowsArm64": %s,
                  "linuxX64": %s,
                  "linuxArm64": %s,
                  "macosX64": %s,
                  "macosArm64": %s
                }
                """.formatted(
                host == TargetTriple.WINDOWS_X64 || crossTarget == TargetTriple.WINDOWS_X64,
                host == TargetTriple.WINDOWS_ARM64 || crossTarget == TargetTriple.WINDOWS_ARM64,
                host == TargetTriple.LINUX_X64 || crossTarget == TargetTriple.LINUX_X64,
                host == TargetTriple.LINUX_ARM64 || crossTarget == TargetTriple.LINUX_ARM64,
                host == TargetTriple.MACOS_X64 || crossTarget == TargetTriple.MACOS_X64,
                host == TargetTriple.MACOS_ARM64 || crossTarget == TargetTriple.MACOS_ARM64);
    }

    private TargetTriple crossTarget(TargetTriple host) {
        return switch (host) {
            case MACOS_ARM64 -> TargetTriple.LINUX_X64;
            case MACOS_X64 -> TargetTriple.LINUX_ARM64;
            case LINUX_X64 -> TargetTriple.MACOS_ARM64;
            case LINUX_ARM64 -> TargetTriple.MACOS_X64;
            case WINDOWS_X64 -> TargetTriple.LINUX_ARM64;
            case WINDOWS_ARM64 -> TargetTriple.LINUX_X64;
        };
    }

    private MethodVisitor beginMain(ClassWriter writer) {
        MethodVisitor main = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null);
        main.visitCode();
        return main;
    }

    private MethodVisitor beginMainThrows(ClassWriter writer, String exception) {
        MethodVisitor main = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "main",
                "([Ljava/lang/String;)V",
                null,
                new String[] {exception});
        main.visitCode();
        return main;
    }

    private void assertSkippedMethodPreserved(CorpusRunResult run, String selector) throws Exception {
        SelectorParts parts = SelectorParts.parse(selector);
        JsonObject skippedRoot = JsonParser.parseString(Files.readString(
                        run.reportPaths().reports().get("skipped-method-report.json")))
                .getAsJsonObject();
        JsonObject skipped = reportMethod(
                skippedRoot,
                "entries",
                parts,
                true);
        assertEquals("skipped", skipped.get("status").getAsString(), selector);
        assertTrue(skipped.get("hasCode").getAsBoolean(), selector);

        JsonObject loweringRoot = JsonParser.parseString(Files.readString(
                        run.reportPaths().reports().get("lowering-report.json")))
                .getAsJsonObject();
        JsonObject lowering = reportMethod(
                loweringRoot,
                "requestedMethods",
                parts,
                false);
        assertEquals("skipped", lowering.get("status").getAsString(), selector);
        assertTrue(lowering.get("rewriteStrategy").isJsonNull(), selector);
        assertTrue(lowering.get("nativeSymbol").isJsonNull(), selector);
        assertTrue(lowering.get("registrationOwner").isJsonNull(), selector);
        assertTrue(lowering.get("nativeImplementationPath").isJsonNull(), selector);

        assertFalse(run.pipelineResult().nativeRegistrationPlan().entries().stream()
                .anyMatch(entry -> entry.registrationOwner().equals(parts.owner())
                        && entry.methodName().equals(parts.method())
                        && entry.descriptor().equals(parts.descriptor())), selector);

        var originalMethod = jarMethod(run.inputJar(), parts);
        var outputMethod = jarMethod(run.pipelineResult().outputJar(), parts);
        assertFalse((outputMethod.access & ACC_NATIVE) != 0, selector);
        assertFalse((outputMethod.access & ACC_ABSTRACT) != 0, selector);
        assertTrue(outputMethod.instructions != null && outputMethod.instructions.size() > 0, selector);
        assertEquals(executableOpcodes(originalMethod), executableOpcodes(outputMethod), selector);
    }

    private JsonObject reportMethod(
            JsonObject root,
            String arrayName,
            SelectorParts parts,
            boolean matchSelector) {
        for (var element : root.getAsJsonArray(arrayName)) {
            JsonObject method = element.getAsJsonObject();
            boolean matches = matchSelector
                    ? method.get("selector").getAsString().equals(parts.selector())
                    : method.get("class").getAsString().equals(parts.owner())
                            && method.get("method").getAsString().equals(parts.method())
                            && method.get("descriptor").getAsString().equals(parts.descriptor());
            if (matches) {
                return method;
            }
        }
        throw new AssertionError("missing " + parts.selector() + " in " + arrayName);
    }

    private org.objectweb.asm.tree.MethodNode jarMethod(Path jarPath, SelectorParts parts) throws Exception {
        try (JarFile jarFile = new JarFile(jarPath.toFile(), false)) {
            var classEntry = jarFile.getJarEntry(parts.owner() + ".class");
            assertTrue(classEntry != null, parts.owner());
            ClassNode classNode = new ClassNode();
            try (var input = jarFile.getInputStream(classEntry)) {
                new ClassReader(input).accept(classNode, ClassReader.SKIP_DEBUG);
            }
            return classNode.methods.stream()
                    .filter(method -> method.name.equals(parts.method())
                            && method.desc.equals(parts.descriptor()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("missing output method " + parts.selector()));
        }
    }

    private List<Integer> executableOpcodes(org.objectweb.asm.tree.MethodNode method) {
        return java.util.Arrays.stream(method.instructions.toArray())
                .map(instruction -> instruction.getOpcode())
                .filter(opcode -> opcode >= 0)
                .toList();
    }

    private void assertNoEmbeddedBytecodeSurfaces(CorpusRunResult run) throws Exception {
        String packagingReport = Files.readString(run.reportPaths().reports().get("packaging-report.json"));
        assertFalse(packagingReport.contains("\"fallbackBlobs\""), packagingReport);
        assertFalse(packagingReport.contains("nativeEmbeddedClassBlob"), packagingReport);
        assertFalse(packagingReport.contains("fallbackBlobEncodingV1"), packagingReport);

        String artifactAudit = Files.readString(run.reportPaths().reports().get("artifact-audit.json"));
        assertTrue(artifactAudit.contains("NO_EMBEDDED_BYTECODE_WORKSPACE_SURFACES"), artifactAudit);

        try (JarFile jarFile = new JarFile(run.pipelineResult().outputJar().toFile(), false)) {
            List<String> entries = jarFile.stream().map(entry -> entry.getName()).toList();
            assertFalse(entries.contains("xyz/melodysky/runtime/fallback/J2llFallbackSupport.class"), entries.toString());
            assertFalse(entries.contains("xyz/melodysky/runtime/loader/J2llNativeLoaderSupport.class"), entries.toString());
            assertFalse(entries.stream()
                    .anyMatch(entry -> entry.startsWith("j2ll/generated/")
                            && entry.endsWith("/NativeLoader.class")), entries.toString());

            assertFalse(entries.stream()
                    .map(entry -> entry.toLowerCase(java.util.Locale.ROOT))
                    .anyMatch(entry -> entry.startsWith("meta-inf/j2ll/")), entries.toString());
        }
    }

    private record SelectorParts(String selector, String owner, String method, String descriptor) {
        private static SelectorParts parse(String selector) {
            int ownerEnd = selector.indexOf('#');
            int methodEnd = selector.indexOf('!', ownerEnd + 1);
            if (ownerEnd <= 0 || methodEnd <= ownerEnd + 1 || methodEnd == selector.length() - 1) {
                throw new IllegalArgumentException("invalid method selector: " + selector);
            }
            return new SelectorParts(
                    selector,
                    selector.substring(0, ownerEnd),
                    selector.substring(ownerEnd + 1, methodEnd),
                    selector.substring(methodEnd + 1));
        }
    }

    private String runMismatch(CorpusRunResult run) {
        if (run.originalRun() == null || run.outputRun() == null) {
            return "missing child JVM run";
        }
        return "originalExit=%d outputExit=%d originalStdout=%s outputStdout=%s originalStderr=%s outputStderr=%s"
                .formatted(
                        run.originalRun().exitCode(),
                        run.outputRun().exitCode(),
                        quote(run.originalRun().stdout()),
                        quote(run.outputRun().stdout()),
                        quote(run.originalRun().stderr()),
                        quote(run.outputRun().stderr()));
    }

    private String quote(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\\n");
    }

    private void endMain(MethodVisitor main) {
        main.visitInsn(RETURN);
        main.visitMaxs(0, 0);
        main.visitEnd();
    }
}
