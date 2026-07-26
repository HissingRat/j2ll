package xyz.melodysky.report;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReleaseReadinessGateTest {
    @TempDir
    Path temp;

    @Test
    void passesWhenRequiredReportsAndReleaseFieldsExist() throws Exception {
        writeCompleteReports(temp);

        ReleaseReadinessResult result = new ReleaseReadinessGate().evaluate(temp);
        String json = new ReleaseReadinessWriter().json(result);

        assertTrue(result.passed(), json);
        assertTrue(json.contains("\"status\": \"passed\""));
        assertTrue(json.contains("\"blockerEvidenceComplete\""));
        assertTrue(json.contains("\"targetEvidenceComplete\": true"));
        assertTrue(json.contains("\"metadataConsistencyPassed\": true"));
        assertTrue(json.contains("\"blockingSensitiveFactsPassed\": true"));
        assertTrue(json.contains("\"targetPackagePlanComplete\": true"));
        assertTrue(json.contains("\"finalArtifactWritten\": true"));
        assertTrue(json.contains("\"strictModePassed\": false"));
        assertTrue(json.contains("\"name\": \"packaging.targetArtifacts\""));
        assertTrue(json.contains("\"reasonCode\": \"KNOWN_BLOCKERS_REPORTED\""));
    }

    @Test
    void failsClearlyWhenRequiredReportIsMissing() throws Exception {
        writeCompleteReports(temp);
        Files.delete(temp.resolve("reports/support-matrix.json"));

        ReleaseReadinessResult result = new ReleaseReadinessGate().evaluate(temp);
        String json = new ReleaseReadinessWriter().json(result);

        assertFalse(result.passed(), json);
        assertTrue(json.contains("\"status\": \"failed\""));
        assertTrue(json.contains("\"reasonCode\": \"REPORT_MISSING\""));
        assertTrue(json.contains("\"missingEvidence\""));
        assertTrue(json.contains("\"type\": \"missingReport\""));
        assertTrue(json.contains("\"reportPath\": \"reports/support-matrix.json\""));
        assertTrue(json.contains("support-matrix.json is missing"));
    }

    @Test
    void fieldInternalizationReportIsRequiredReadinessEvidence() throws Exception {
        writeCompleteReports(temp);
        Files.delete(temp.resolve("reports/field-internalization-report.json"));

        ReleaseReadinessResult result = new ReleaseReadinessGate().evaluate(temp);
        String json = new ReleaseReadinessWriter().json(result);

        assertFalse(result.passed(), json);
        assertTrue(json.contains("\"name\": \"report:field-internalization-report.json\""), json);
        assertTrue(json.contains("\"reasonCode\": \"REPORT_MISSING\""), json);
        assertTrue(json.contains("\"reportPath\": \"reports/field-internalization-report.json\""), json);
    }

    @Test
    void strictSuiteModeRequiresReleaseSuiteSummary() throws Exception {
        writeCompleteReports(temp);

        ReleaseReadinessResult result = new ReleaseReadinessGate().evaluate(temp, true);
        String json = new ReleaseReadinessWriter().json(result);

        assertFalse(result.passed(), json);
        assertTrue(json.contains("\"name\": \"releaseSuite.summary\""));
        assertTrue(json.contains("\"reasonCode\": \"REPORT_MISSING\""));
    }

    @Test
    void strictSuiteModePassesWithSuiteSummaryAndBlockerCoverage() throws Exception {
        writeCompleteReports(temp);
        Files.writeString(temp.resolve("reports/release-suite-summary.json"), strictSuiteSummary("""
                "UNSAFE_RAW_MEMORY_FALLBACK": "expected"
                """, "true", "true", outputRun(), "\"UNSAFE_RAW_MEMORY_FALLBACK\""));

        ReleaseReadinessResult result = new ReleaseReadinessGate().evaluate(temp, true);
        String json = new ReleaseReadinessWriter().json(result);

        assertTrue(result.passed(), json);
        assertTrue(json.contains("\"reasonCode\": \"RELEASE_SUITE_SUMMARY_REPORTED\""));
        assertTrue(json.contains("\"reasonCode\": \"KNOWN_BLOCKERS_FIELDS_REPORTED\""));
        assertTrue(json.contains("\"reasonCode\": \"KNOWN_BLOCKERS_SUITE_OR_SEED_COVERAGE\""));
        assertTrue(json.contains("\"reasonCode\": \"RELEASE_SUITE_EXPECTATIONS_MATCH\""));
        assertTrue(json.contains("\"suiteCoverageByBlocker\""));
        assertTrue(json.contains("\"evidenceType\": \"releaseSuiteCase\""));
        assertTrue(json.contains("\"blockerEvidenceComplete\": true"));
        assertTrue(json.contains("\"strictModePassed\": true"));
    }

    @Test
    void missingBlockerReasonFailsReadiness() throws Exception {
        writeCompleteReports(temp);
        Files.writeString(temp.resolve("reports/known-blockers.json"), """
                {"blockers":[{"id":"broken","reasonCode":"","severity":"rc-blocker","targetMilestone":"rc","currentBehavior":"fallback","reportLocation":"reports/x.json","suggestedFuturePath":"fix"}]}
                """);

        ReleaseReadinessResult result = new ReleaseReadinessGate().evaluate(temp);
        String json = new ReleaseReadinessWriter().json(result);

        assertFalse(result.passed(), json);
        assertTrue(json.contains("\"reasonCode\": \"KNOWN_BLOCKERS_FIELDS_MISSING\""));
    }

    @Test
    void missingBlockerSuiteCoverageFailsStrictReadiness() throws Exception {
        writeCompleteReports(temp);
        Files.writeString(temp.resolve("reports/release-suite-summary.json"), strictSuiteSummary("""
                "OTHER_REASON": "expected"
                """, "true", "true", outputRun(), "\"OTHER_REASON\""));

        ReleaseReadinessResult result = new ReleaseReadinessGate().evaluate(temp, true);
        String json = new ReleaseReadinessWriter().json(result);

        assertFalse(result.passed(), json);
        assertTrue(json.contains("\"reasonCode\": \"KNOWN_BLOCKERS_SUITE_COVERAGE_MISSING\""));
        assertTrue(json.contains("UNSAFE_RAW_MEMORY_FALLBACK"));
    }

    @Test
    void strictSuiteModeDoesNotRequireFutureOrExplicitNongoalSuiteEvidence() throws Exception {
        writeCompleteReports(temp);
        Files.writeString(temp.resolve("reports/known-blockers.json"), """
                {
                  "blockers": [
                    {
                      "id": "future",
                      "reasonCode": "FUTURE_REASON",
                      "severity": "future-blocker",
                      "targetMilestone": "post-rc",
                      "currentBehavior": "fallback",
                      "reportLocation": "reports/lowering-report.json",
                      "suggestedFuturePath": "future helper"
                    },
                    {
                      "id": "native-image",
                      "reasonCode": "EXPLICIT_NONGOAL_STANDALONE_NATIVE_IMAGE",
                      "severity": "non-goal",
                      "targetMilestone": "explicit-nongoal",
                      "currentBehavior": "JVM-hosted only",
                      "reportLocation": "AGENTS.md",
                      "suggestedFuturePath": "stay non-goal"
                    }
                  ]
                }
                """);
        Files.writeString(temp.resolve("reports/support-matrix.json"), """
                {"features":[{"feature":"future","status":"FALLBACK","reasonCode":"FUTURE_REASON","testCoverage":"test"}]}
                """);
        Files.writeString(temp.resolve("reports/release-suite-summary.json"),
                strictSuiteSummary("", "true", "true", outputRun(), ""));
        refreshIndex(temp);

        ReleaseReadinessResult result = new ReleaseReadinessGate().evaluate(temp, true);
        String json = new ReleaseReadinessWriter().json(result);

        assertTrue(result.passed(), json);
        assertTrue(json.contains("\"evidenceType\": \"notRequiredUntilMilestone\""));
        assertTrue(json.contains("\"evidenceType\": \"explicitNonGoal\""));
    }

    @Test
    void rcProfileMissingCategoriesFailStrictReadiness() throws Exception {
        writeCompleteReports(temp);
        Files.writeString(temp.resolve("reports/release-suite-summary.json"), rcSuiteSummaryWithMissingCategories());

        ReleaseReadinessResult result = new ReleaseReadinessGate().evaluate(temp, true);
        String json = new ReleaseReadinessWriter().json(result);

        assertFalse(result.passed(), json);
        assertTrue(json.contains("\"reasonCode\": \"RELEASE_SUITE_RC_CATEGORIES_MISSING\""));
        assertTrue(json.contains("\"type\": \"missingSuiteCategory\""));
        assertTrue(json.contains("\"reportPath\": \"reports/release-suite-summary.json\""));
        assertTrue(json.contains("artifact-audit-failure"));
    }

    @Test
    void expectedFailureWithoutMatchingDiagnosticFailsStrictReadiness() throws Exception {
        writeCompleteReports(temp);
        Files.writeString(temp.resolve("reports/release-suite-summary.json"), strictSuiteSummary("""
                "UNSAFE_RAW_MEMORY_FALLBACK": "expected"
                """, "false", "false", "null", ""));

        ReleaseReadinessResult result = new ReleaseReadinessGate().evaluate(temp, true);
        String json = new ReleaseReadinessWriter().json(result);

        assertFalse(result.passed(), json);
        assertTrue(json.contains("\"reasonCode\": \"RELEASE_SUITE_EXPECTATION_MISMATCH\""));
    }

    @Test
    void requiredTargetFailureBlocksFinalArtifactAndStillHasTargetEvidence() throws Exception {
        writeCompleteReports(temp);
        Files.delete(temp.resolve("input.jar"));
        Files.writeString(temp.resolve("reports/packaging-report.json"), failedTargetPackagingReport());
        Files.writeString(temp.resolve("reports/release-suite-summary.json"), strictSuiteSummary("""
                "UNSAFE_RAW_MEMORY_FALLBACK": "expected"
                """, "false", "false", "null", "\"UNSAFE_RAW_MEMORY_FALLBACK\""));

        ReleaseReadinessResult result = new ReleaseReadinessGate().evaluate(temp, true);
        String json = new ReleaseReadinessWriter().json(result);

        assertFalse(result.passed(), json);
        assertTrue(json.contains("\"targetEvidenceComplete\": true"));
        assertTrue(json.contains("\"finalArtifactWritten\": false"));
        assertTrue(json.contains("\"reasonCode\": \"FINAL_ARTIFACT_BLOCKED_BY_REQUIRED_TARGET\""));
    }

    @Test
    void metadataConsistencyFailureExplainsStrictReadinessFailure() throws Exception {
        writeCompleteReports(temp);
        Files.writeString(temp.resolve("reports/artifact-audit.json"), """
                {
                  "passed": false,
                  "checkedSensitiveFacts": [],
                  "checks": [
                    {
                      "name": "metadata.nativeLibrariesTargetArtifacts",
                      "status": "failed",
                      "reasonCode": "METADATA_CONSISTENCY_FAILED",
                      "message": "native metadata mismatch"
                    }
                  ]
                }
                """);

        ReleaseReadinessResult result = new ReleaseReadinessGate().evaluate(temp);
        String json = new ReleaseReadinessWriter().json(result);

        assertFalse(result.passed(), json);
        assertTrue(json.contains("\"metadataConsistencyPassed\": false"));
        assertTrue(json.contains("\"type\": \"metadataConsistencyMissing\""));
        assertTrue(json.contains("\"reasonCode\": \"METADATA_CONSISTENCY_FAILED\""));
    }

    @Test
    void blockingSensitivePlaintextFailureExplainsReadinessFailure() throws Exception {
        writeCompleteReports(temp);
        Files.writeString(temp.resolve("reports/artifact-audit.json"), """
                {
                  "passed": false,
                  "checkedSensitiveFacts": [
                    {
                      "literalHash": "%s",
                      "sourceMethod": "pkg/Foo#run!()V",
                      "passName": "STRING_ENCRYPTION",
                      "pathKind": "HELPER_PATH_STABLE_GENERATED_C_SURFACE",
                      "gateMode": "blocking",
                      "promotionReason": "stableGeneratedCSurface"
                    }
                  ],
                  "checks": [
                    {"name":"metadata.reportsManifest","status":"passed","reasonCode":"J2LL_REPORTS_MANIFEST_MATCH","message":"ok"},
                    {"name":"plaintext.forbiddenStrings","status":"failed","reasonCode":"FORBIDDEN_PLAINTEXT_FOUND","message":"hash-only hit"}
                  ]
                }
                """.formatted("a".repeat(64)));

        ReleaseReadinessResult result = new ReleaseReadinessGate().evaluate(temp);
        String json = new ReleaseReadinessWriter().json(result);

        assertFalse(result.passed(), json);
        assertTrue(json.contains("\"blockingSensitiveFactsPassed\": false"));
        assertTrue(json.contains("\"type\": \"blockingSensitivePlaintextLeak\""));
        assertTrue(json.contains("\"reasonCode\": \"BLOCKING_SENSITIVE_PLAINTEXT_LEAK\""));
    }

    @Test
    void betaProfilePassesWithCliDocsExamplesAndReportIndexEvidence() throws Exception {
        writeCompleteReports(temp);
        Files.writeString(temp.resolve("reports/release-suite-summary.json"), betaSuiteSummary(""));

        ReleaseReadinessResult result = new ReleaseReadinessGate().evaluate(temp, true);
        String json = new ReleaseReadinessWriter().json(result);

        assertTrue(result.passed(), json);
        assertTrue(json.contains("\"betaProfilePassed\": true"));
        assertTrue(json.contains("\"cliArtifactSmokePassed\": true"));
        assertTrue(json.contains("\"docsExamplesValidated\": true"));
        assertTrue(json.contains("\"betaMissingEvidence\": []"));
    }

    @Test
    void betaProfileReportsMissingEvidenceWhenReportIndexIsMissing() throws Exception {
        writeCompleteReports(temp);
        Files.delete(temp.resolve("reports/index.json"));
        Files.writeString(temp.resolve("reports/release-suite-summary.json"), betaSuiteSummary(""));

        ReleaseReadinessResult result = new ReleaseReadinessGate().evaluate(temp, true);
        String json = new ReleaseReadinessWriter().json(result);

        assertFalse(result.passed(), json);
        assertTrue(json.contains("\"betaProfilePassed\": false"));
        assertTrue(json.contains("\"reasonCode\": \"BETA_REPORT_INDEX_MISSING\""));
        assertTrue(json.contains("missing reports/index.json"));
    }

    @Test
    void betaBlockerWithoutSuiteEvidenceFailsBetaProfile() throws Exception {
        writeCompleteReports(temp);
        Files.writeString(temp.resolve("reports/known-blockers.json"), """
                {"blockers":[{"id":"beta-gap","reasonCode":"BETA_GAP_WITHOUT_EVIDENCE","severity":"beta-blocker","targetMilestone":"beta","currentBehavior":"fallback","reportLocation":"reports/lowering-report.json","suggestedFuturePath":"add beta fixture"}]}
                """);
        Files.writeString(temp.resolve("reports/support-matrix.json"), """
                {"features":[{"feature":"beta.gap","status":"FALLBACK","reasonCode":"BETA_GAP_WITHOUT_EVIDENCE","testCoverage":"pending"}]}
                """);
        Files.writeString(temp.resolve("reports/release-suite-summary.json"), betaSuiteSummary(""));
        refreshIndex(temp);

        ReleaseReadinessResult result = new ReleaseReadinessGate().evaluate(temp, true);
        String json = new ReleaseReadinessWriter().json(result);

        assertFalse(result.passed(), json);
        assertTrue(json.contains("\"betaProfilePassed\": false"), json);
        assertTrue(json.contains("uncovered beta blocker BETA_GAP_WITHOUT_EVIDENCE"), json);
        assertTrue(json.contains("\"reasonCode\": \"RELEASE_SUITE_BETA_CATEGORIES_MISSING\""), json);
    }


    private void writeCompleteReports(Path workspace) throws Exception {
        Path reports = workspace.resolve("reports");
        Files.createDirectories(reports);
        Files.writeString(workspace.resolve("input.jar"), "fixture");
        Files.writeString(reports.resolve("diagnostics.json"), "{\"diagnostics\":[]}\n");
        Files.writeString(reports.resolve("artifact-audit.json"), """
                {
                  "passed": true,
                  "checkedSensitiveFacts": [],
                  "checks": [
                    {"name":"jar.noPlainFallbackClasses","status":"passed","reasonCode":"NO_PLAIN_FALLBACK_CLASSES","message":"ok"},
                    {"name":"metadata.nativeLibrariesTargetArtifacts","status":"passed","reasonCode":"J2LL_NATIVE_METADATA_TARGET_ARTIFACTS_MATCH","message":"ok"},
                    {"name":"metadata.reportsManifest","status":"passed","reasonCode":"J2LL_REPORTS_MANIFEST_MATCH","message":"ok"}
                  ]
                }
                """);
        Files.writeString(reports.resolve("field-internalization-report.json"), """
                {
                  "schemaVersion": 1,
                  "reportVersion": 1,
                  "enabled": false,
                  "decisions": []
                }
                """);
        Files.writeString(reports.resolve("frontend-skip-report.json"), "{\"skipped\":[]}\n");
        Files.writeString(reports.resolve("known-blockers.json"), """
                {"blockers":[{"id":"raw","reasonCode":"UNSAFE_RAW_MEMORY_FALLBACK","severity":"rc-blocker","targetMilestone":"rc","currentBehavior":"fallback","reportLocation":"reports/lowering-report.json","suggestedFuturePath":"helper"}]}
                """);
        Files.writeString(reports.resolve("lowering-report.json"), "{\"methods\":[]}\n");
        Files.writeString(reports.resolve("opcode-support-matrix.json"), "{\"opcodes\":[]}\n");
        Files.writeString(reports.resolve("packaging-report.json"), successfulPackagingReport());
        Files.writeString(reports.resolve("protection-report.json"), "{\"passes\":[]}\n");
        Files.writeString(reports.resolve("support-matrix.json"), """
                {"features":[{"feature":"unsafe.raw","status":"FALLBACK","reasonCode":"UNSAFE_RAW_MEMORY_FALLBACK","testCoverage":"test"}]}
                """);
        Files.writeString(reports.resolve("symbol-audit.json"), "{\"libraries\":[]}\n");
        refreshIndex(workspace);
    }

    private void refreshIndex(Path workspace) throws Exception {
        new SummaryReportWriter().write(workspace, "build", true);
        new SummaryMarkdownWriter().write(workspace);
        new ReportIndexWriter().write(workspace);
    }

    private String strictSuiteSummary(
            String expectedStatuses,
            String expectedPipelineSuccess,
            String pipelineSuccessful,
            String output,
            String diagnostics) {
        return """
                {
                  "schemaVersion": 1,
                  "suiteName": "release",
                  "aggregate": {
                    "totalCases": 1,
                    "successCases": 1,
                    "expectedFailureCases": 0,
                    "casesByCategory": {"boundary": 1},
                    "casesByFeature": {"feature": 1},
                    "strictEvidenceComplete": true,
                    "determinismEvidenceComplete": true
                  },
                  "determinismEvidenceComplete": true,
                  "cases": [
                    {
                      "name": "case",
                      "category": "boundary",
                      "features": ["feature"],
                      "expectedSupportStatuses": {%s},
                      "protectionEnabled": false,
                      "signaturePolicy": "fail",
                      "expectedPipelineSuccess": %s,
                      "pipelineSuccessful": %s,
                      "passed": true,
                      "original": {"exitCode": 0, "stdout": "", "stderr": ""},
                      "output": %s,
                      "reports": [
                        "diagnostics.json",
                        "artifact-audit.json",
                        "field-internalization-report.json",
                        "frontend-skip-report.json",
                        "known-blockers.json",
                        "lowering-report.json",
                        "opcode-support-matrix.json",
                        "packaging-report.json",
                        "protection-report.json",
                        "support-matrix.json",
                        "symbol-audit.json"
                      ],
                      "diagnostics": [%s]
                    }
                  ]
                }
                """.formatted(expectedStatuses, expectedPipelineSuccess, pipelineSuccessful, output, diagnostics);
    }

    private String betaSuiteSummary(String missingCategories) {
        String missing = missingCategories.isBlank() ? "" : "\"" + missingCategories + "\"";
        return """
                {
                  "schemaVersion": 1,
                  "reportVersion": 1,
                  "suiteName": "release-beta",
                  "profile": "beta",
                  "requiredCategories": ["cli-artifact-smoke", "docs-examples-validated", "report-index"],
                  "missingCategories": [%s],
                  "aggregate": {
                    "totalCases": 1,
                    "successCases": 1,
                    "expectedFailureCases": 0,
                    "casesByCategory": {"cli-artifact-smoke": 1},
                    "casesByFeature": {
                      "cli-artifact-smoke": 1,
                      "docs-examples-validated": 1,
                      "report-index": 1
                    },
                    "strictEvidenceComplete": true,
                    "determinismEvidenceComplete": true
                  },
                  "determinismEvidenceComplete": true,
                  "cases": [
                    {
                      "name": "beta-cli-docs",
                      "category": "cli-artifact-smoke",
                      "features": ["docs-examples-validated", "report-index"],
                      "expectedSupportStatuses": {"UNSAFE_RAW_MEMORY_FALLBACK": "expected"},
                      "protectionEnabled": false,
                      "signaturePolicy": "fail",
                      "expectedPipelineSuccess": true,
                      "pipelineSuccessful": true,
                      "passed": true,
                      "original": {"exitCode": 0, "stdout": "", "stderr": ""},
                      "output": {"exitCode": 0, "stdout": "", "stderr": ""},
                      "reports": [
                        "diagnostics.json",
                        "artifact-audit.json",
                        "field-internalization-report.json",
                        "frontend-skip-report.json",
                        "known-blockers.json",
                        "lowering-report.json",
                        "opcode-support-matrix.json",
                        "packaging-report.json",
                        "protection-report.json",
                        "support-matrix.json",
                        "symbol-audit.json",
                        "index.json"
                      ],
                      "diagnostics": []
                    }
                  ]
                }
                """.formatted(missing);
    }

    private String outputRun() {
        return "{\"exitCode\": 0, \"stdout\": \"\", \"stderr\": \"\"}";
    }

    private String rcSuiteSummaryWithMissingCategories() {
        return """
                {
                  "schemaVersion": 1,
                  "reportVersion": 1,
                  "suiteName": "release-rc",
                  "profile": "rc",
                  "requiredCategories": ["llvm-native", "artifact-audit-failure"],
                  "missingCategories": ["artifact-audit-failure"],
                  "aggregate": {
                    "totalCases": 1,
                    "successCases": 1,
                    "expectedFailureCases": 0,
                    "casesByCategory": {"llvm-native": 1},
                    "casesByFeature": {"llvm-native": 1},
                    "strictEvidenceComplete": true,
                    "determinismEvidenceComplete": true
                  },
                  "determinismEvidenceComplete": true,
                  "cases": [
                    {
                      "name": "case",
                      "category": "llvm-native",
                      "features": ["llvm-native"],
                      "expectedSupportStatuses": {"UNSAFE_RAW_MEMORY_FALLBACK": "expected"},
                      "protectionEnabled": false,
                      "signaturePolicy": "fail",
                      "expectedPipelineSuccess": true,
                      "pipelineSuccessful": true,
                      "passed": true,
                      "original": {"exitCode": 0, "stdout": "", "stderr": ""},
                      "output": {"exitCode": 0, "stdout": "", "stderr": ""},
                      "reports": [
                        "diagnostics.json",
                        "artifact-audit.json",
                        "field-internalization-report.json",
                        "frontend-skip-report.json",
                        "known-blockers.json",
                        "lowering-report.json",
                        "opcode-support-matrix.json",
                        "packaging-report.json",
                        "protection-report.json",
                        "support-matrix.json",
                        "symbol-audit.json"
                      ],
                      "diagnostics": ["UNSAFE_RAW_MEMORY_FALLBACK"]
                    }
                  ]
                }
                """;
    }

    private String successfulPackagingReport() {
        return """
                {
                  "outputJar": "input.jar",
                  "signatureAction": {},
                  "fallbackBlobs": [],
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
                        "expectedResourcePath": "native/arm64-macos.dylib",
                        "loaderExtractionPathPolicy": "contentAddressedTempCacheBySha256",
                        "symbolVisibilityPolicy": "allowlistOnlyJniOnLoadAndBootstrap",
                        "windowsPdbPolicy": "notApplicable",
                        "actualArtifactPath": "native/arm64-macos.dylib",
                        "actualJarPath": "native/arm64-macos.dylib",
                        "actualSha256": "%s",
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
                """.formatted("a".repeat(64));
    }

    private String failedTargetPackagingReport() {
        return """
                {
                  "outputJar": "input.jar",
                  "signatureAction": {},
                  "fallbackBlobs": [],
                  "zigToolchain": {
                    "targetArtifacts": [
                      {
                        "target": "linux-x64",
                        "required": true,
                        "currentHost": false,
                        "buildable": false,
                        "osClassifier": "linux",
                        "archClassifier": "x64",
                        "libraryExtension": "so",
                        "libraryName": "j2ll",
                        "zigTarget": "x86_64-linux.3.2-gnu.2.17",
                        "expectedArtifactPath": "native/x64-linux.so",
                        "expectedArtifactName": "x64-linux.so",
                        "expectedResourcePath": "native/x64-linux.so",
                        "loaderExtractionPathPolicy": "contentAddressedTempCacheBySha256",
                        "symbolVisibilityPolicy": "allowlistOnlyJniOnLoadAndBootstrap",
                        "windowsPdbPolicy": "notApplicable",
                        "actualArtifactPath": null,
                        "actualJarPath": null,
                        "actualSha256": null,
                        "exportedSymbols": [],
                        "status": "failed",
                        "reasonCode": "ZIG_TARGET_UNBUILDABLE",
                        "reason": "managed Zig failed to produce the required target artifact",
                        "requiredCapability": "managedZig0.15.2CrossTargetSharedLibrary",
                        "platformSdkRequirement": "managed Zig 0.15.2 ELF/Linux libc target support; no host Linux SDK required",
                        "failureKind": "zigBuildFailed",
                        "buildLogTail": "matrix-wide Zig link failed for linux-x64"
                      }
                    ]
                  }
                }
                """;
    }
}
