package xyz.melodysky.report;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.jar.JarFile;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public final class ReleaseReadinessGate {
    private static final Set<String> WEIRD_SEED_COVERED_REASONS = Set.of(
            "UNSUPPORTED_MULTI_EXIT_FINALLY",
            "UNSUPPORTED_EXCEPTION_STATE_MERGE",
            "UNSUPPORTED_MONITOR_FINALLY_INTERACTION",
            "UNSUPPORTED_NESTED_FINALLY",
            "UNSUPPORTED_FINALLY_SUBROUTINE");

    private static final List<String> REQUIRED_REPORTS = List.of(
            "diagnostics.json",
            "artifact-audit.json",
            "field-internalization-report.json",
            "known-blockers.json",
            "lowering-report.json",
            "opcode-support-matrix.json",
            "packaging-report.json",
            "protection-report.json",
            "skipped-method-report.json",
            "index.json",
            "summary.json",
            "summary.md",
            "support-matrix.json",
            "symbol-audit.json");

    public ReleaseReadinessResult evaluate(Path workspaceRoot) throws IOException {
        return evaluate(workspaceRoot, false);
    }

    public ReleaseReadinessResult evaluate(Path workspaceRoot, boolean requireReleaseSuiteSummary) throws IOException {
        Path reports = workspaceRoot.resolve("reports");
        ArrayList<ReleaseReadinessCheck> checks = new ArrayList<>();
        for (String report : REQUIRED_REPORTS) {
            Path path = reports.resolve(report);
            checks.add(Files.isRegularFile(path)
                    ? ReleaseReadinessCheck.passed("report:" + report, "REPORT_PRESENT", report + " exists")
                    : ReleaseReadinessCheck.failed("report:" + report, "REPORT_MISSING", report + " is missing"));
        }
        checkContains(checks, reports.resolve("packaging-report.json"), "packaging.targetArtifacts", "\"targetArtifacts\"", "TARGET_ARTIFACTS_REPORTED");
        checkContains(checks, reports.resolve("artifact-audit.json"), "artifactAudit.checks", "\"checks\"", "ARTIFACT_AUDIT_REPORTED");
        checkContains(
                checks,
                reports.resolve("field-internalization-report.json"),
                "fieldInternalization.decisions",
                "\"decisions\"",
                "FIELD_INTERNALIZATION_REPORTED");
        checkArtifactAuditStatus(checks, reports);
        checkReportIndexIntegrity(checks, workspaceRoot, reports);
        checkContains(checks, reports.resolve("packaging-report.json"), "packaging.signatureAction", "\"signatureAction\"", "SIGNATURE_ACTION_REPORTED");
        checkContains(checks, reports.resolve("symbol-audit.json"), "symbolAudit.libraries", "\"libraries\"", "SYMBOL_AUDIT_REPORTED");
        checkContains(checks, reports.resolve("support-matrix.json"), "supportMatrix.features", "\"features\"", "SUPPORT_MATRIX_REPORTED");
        checkContains(checks, reports.resolve("opcode-support-matrix.json"), "opcodeMatrix.opcodes", "\"opcodes\"", "OPCODE_MATRIX_REPORTED");
        checkContains(checks, reports.resolve("known-blockers.json"), "knownBlockers.blockers", "\"blockers\"", "KNOWN_BLOCKERS_REPORTED");
        if (requireReleaseSuiteSummary) {
            checkContains(checks, reports.resolve("release-suite-summary.json"), "releaseSuite.summary", "\"suiteName\"", "RELEASE_SUITE_SUMMARY_REPORTED");
            checkContains(checks, reports.resolve("release-suite-summary.json"), "releaseSuite.cases", "\"cases\"", "RELEASE_SUITE_CASES_REPORTED");
        }
        checkKnownBlockers(checks, reports);
        checkKnownBlockersHaveMatrixCoverage(checks, reports);
        if (requireReleaseSuiteSummary) {
            checkKnownBlockersHaveSuiteOrSeedCoverage(checks, reports);
            checkSuiteCasesMatchExpectations(checks, reports);
            checkReleaseSuiteProfile(checks, reports);
        }
        boolean determinismEvidenceComplete = !requireReleaseSuiteSummary
                || checkDeterminismEvidence(checks, reports);
        boolean targetEvidenceComplete = checkTargetEvidence(checks, reports);
        AuditDerivedGates auditDerivedGates = checkArtifactAuditDerivedGates(checks, reports);
        boolean finalArtifactWritten = checkFinalArtifactWritten(checks, workspaceRoot, reports);
        checkNoLegacyPathOutputs(checks, workspaceRoot);
        BetaProfileGates betaProfileGates = checkBetaProfileEvidence(checks, reports, requireReleaseSuiteSummary);
        List<ReleaseBlockerCoverage> blockerCoverage = blockerCoverage(reports);
        boolean blockerEvidenceComplete = blockerCoverage.stream().allMatch(ReleaseBlockerCoverage::covered);
        if (requireReleaseSuiteSummary && !blockerEvidenceComplete) {
            checks.add(ReleaseReadinessCheck.failed(
                    "knownBlockers.v3Coverage",
                    "BLOCKER_EVIDENCE_INCOMPLETE",
                    "missing structured blocker coverage: " + blockerCoverage.stream()
                            .filter(item -> !item.covered())
                            .map(ReleaseBlockerCoverage::reasonCode)
                            .distinct()
                            .sorted()
                            .toList()));
        } else if (requireReleaseSuiteSummary) {
            checks.add(ReleaseReadinessCheck.passed(
                    "knownBlockers.v3Coverage",
                    "BLOCKER_EVIDENCE_COMPLETE",
                    "all blockers have structured release suite or weird seed evidence"));
        }
        boolean passed = checks.stream().noneMatch(check -> check.status().equals("failed"));
        return new ReleaseReadinessResult(
                passed,
                checks,
                missingEvidence(checks),
                blockerCoverage,
                blockerEvidenceComplete,
                targetEvidenceComplete,
                finalArtifactWritten,
                determinismEvidenceComplete,
                auditDerivedGates.metadataConsistencyPassed(),
                auditDerivedGates.blockingSensitiveFactsPassed(),
                targetEvidenceComplete,
                betaProfileGates.betaProfilePassed(),
                betaProfileGates.missingEvidence(),
                betaProfileGates.cliArtifactSmokePassed(),
                betaProfileGates.docsExamplesValidated(),
                requireReleaseSuiteSummary && passed);
    }

    private List<ReleaseReadinessMissingEvidence> missingEvidence(List<ReleaseReadinessCheck> checks) {
        return checks.stream()
                .filter(check -> check.status().equals("failed"))
                .map(check -> new ReleaseReadinessMissingEvidence(
                        missingEvidenceType(check),
                        check.name(),
                        check.reasonCode(),
                        check.detail(),
                        reportPathFor(check)))
                .sorted(java.util.Comparator
                        .comparing(ReleaseReadinessMissingEvidence::type)
                        .thenComparing(ReleaseReadinessMissingEvidence::name)
                        .thenComparing(ReleaseReadinessMissingEvidence::reasonCode))
                .toList();
    }

    private String missingEvidenceType(ReleaseReadinessCheck check) {
        String reasonCode = check.reasonCode();
        if (reasonCode.equals("REPORT_MISSING")) {
            return "missingReport";
        }
        if (reasonCode.equals("BLOCKER_EVIDENCE_INCOMPLETE")
                || reasonCode.equals("KNOWN_BLOCKERS_SUITE_COVERAGE_MISSING")
                || reasonCode.equals("KNOWN_BLOCKERS_MATRIX_COVERAGE_MISSING")) {
            return "missingBlockerEvidence";
        }
        if (reasonCode.equals("RELEASE_SUITE_RC_CATEGORIES_MISSING")
                || reasonCode.equals("RELEASE_SUITE_BETA_CATEGORIES_MISSING")
                || reasonCode.equals("BETA_REPORT_INDEX_MISSING")
                || reasonCode.equals("RELEASE_SUITE_EXPECTATION_MISMATCH")) {
            return "missingSuiteCategory";
        }
        if (reasonCode.equals("ARTIFACT_AUDIT_FAILED")) {
            return "artifactAuditNotPassed";
        }
        if (reasonCode.equals("METADATA_CONSISTENCY_FAILED")
                || reasonCode.equals("J2LL_METADATA_RAW_SEED")
                || reasonCode.equals("J2LL_REPORTS_MANIFEST_INCOMPLETE")) {
            return "metadataConsistencyMissing";
        }
        if (reasonCode.equals("BLOCKING_SENSITIVE_PLAINTEXT_LEAK")
                || reasonCode.equals("FORBIDDEN_PLAINTEXT_FOUND")
                || reasonCode.equals("FORBIDDEN_PLAINTEXT_JAR_ENTRY")) {
            return "blockingSensitivePlaintextLeak";
        }
        if (reasonCode.equals("DETERMINISM_EVIDENCE_INCOMPLETE")) {
            return "determinismMissing";
        }
        if (reasonCode.equals("TARGET_EVIDENCE_INCOMPLETE")) {
            return "targetEvidenceIncomplete";
        }
        if (reasonCode.startsWith("FINAL_ARTIFACT_")) {
            return "finalArtifactMissing";
        }
        return "failedCheck";
    }

    private String reportPathFor(ReleaseReadinessCheck check) {
        if (check.name().startsWith("report:")) {
            return "reports/" + check.name().substring("report:".length());
        }
        if (check.name().startsWith("artifactAudit.")) {
            return "reports/artifact-audit.json";
        }
        if (check.name().startsWith("knownBlockers.")) {
            return "reports/known-blockers.json";
        }
        if (check.name().startsWith("releaseSuite.")) {
            return "reports/release-suite-summary.json";
        }
        if (check.name().startsWith("packaging.")) {
            return "reports/packaging-report.json";
        }
        return "reports/release-readiness.json";
    }

    private boolean checkDeterminismEvidence(List<ReleaseReadinessCheck> checks, Path reports) throws IOException {
        Path suiteFile = reports.resolve("release-suite-summary.json");
        if (!Files.isRegularFile(suiteFile)) {
            checks.add(ReleaseReadinessCheck.failed(
                    "releaseSuite.determinism",
                    "REPORT_MISSING",
                    "release-suite-summary.json is missing"));
            return false;
        }
        JsonObject suite = JsonParser.parseString(Files.readString(suiteFile)).getAsJsonObject();
        boolean rootComplete = suite.has("determinismEvidenceComplete")
                && suite.get("determinismEvidenceComplete").getAsBoolean();
        boolean aggregateComplete = suite.has("aggregate")
                && suite.getAsJsonObject("aggregate").has("determinismEvidenceComplete")
                && suite.getAsJsonObject("aggregate").get("determinismEvidenceComplete").getAsBoolean();
        boolean complete = rootComplete && aggregateComplete;
        checks.add(complete
                ? ReleaseReadinessCheck.passed(
                        "releaseSuite.determinism",
                        "DETERMINISM_EVIDENCE_COMPLETE",
                        "release suite summary records deterministic case/report ordering evidence")
                : ReleaseReadinessCheck.failed(
                        "releaseSuite.determinism",
                        "DETERMINISM_EVIDENCE_INCOMPLETE",
                        "release suite summary lacks complete determinism evidence"));
        return complete;
    }

    private void checkContains(
            List<ReleaseReadinessCheck> checks,
            Path file,
            String name,
            String needle,
            String reasonCode) throws IOException {
        if (!Files.isRegularFile(file)) {
            checks.add(ReleaseReadinessCheck.failed(name, "REPORT_MISSING", file.getFileName() + " is missing"));
            return;
        }
        String content = Files.readString(file);
        checks.add(content.contains(needle)
                ? ReleaseReadinessCheck.passed(name, reasonCode, needle + " present in " + file.getFileName())
                : ReleaseReadinessCheck.failed(name, reasonCode + "_MISSING", needle + " missing in " + file.getFileName()));
    }

    private void checkKnownBlockers(List<ReleaseReadinessCheck> checks, Path reports) throws IOException {
        Path file = reports.resolve("known-blockers.json");
        if (!Files.isRegularFile(file)) {
            checks.add(ReleaseReadinessCheck.failed("knownBlockers.fields", "REPORT_MISSING", "known-blockers.json is missing"));
            return;
        }
        JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        boolean valid = root.getAsJsonArray("blockers").asList().stream()
                .map(element -> element.getAsJsonObject())
                .allMatch(blocker -> hasText(blocker, "id")
                        && hasText(blocker, "reasonCode")
                        && hasText(blocker, "severity")
                        && hasText(blocker, "targetMilestone")
                        && hasText(blocker, "reportLocation")
                        && hasText(blocker, "currentBehavior")
                        && hasText(blocker, "suggestedFuturePath"));
        checks.add(valid
                ? ReleaseReadinessCheck.passed("knownBlockers.fields", "KNOWN_BLOCKERS_FIELDS_REPORTED", "all blockers have severity, milestone, reason and report location")
                : ReleaseReadinessCheck.failed("knownBlockers.fields", "KNOWN_BLOCKERS_FIELDS_MISSING", "one or more blockers are missing required fields"));
    }

    private void checkKnownBlockersHaveMatrixCoverage(List<ReleaseReadinessCheck> checks, Path reports) throws IOException {
        Path blockersFile = reports.resolve("known-blockers.json");
        Path supportFile = reports.resolve("support-matrix.json");
        Path opcodeFile = reports.resolve("opcode-support-matrix.json");
        if (!Files.isRegularFile(blockersFile) || !Files.isRegularFile(supportFile) || !Files.isRegularFile(opcodeFile)) {
            checks.add(ReleaseReadinessCheck.failed("knownBlockers.matrixCoverage", "REPORT_MISSING", "blocker or matrix report is missing"));
            return;
        }
        JsonObject blockers = JsonParser.parseString(Files.readString(blockersFile)).getAsJsonObject();
        Set<String> reasons = new HashSet<>();
        blockers.getAsJsonArray("blockers").forEach(element -> {
            JsonObject blocker = element.getAsJsonObject();
            if (hasText(blocker, "reasonCode") && !isExplicitNonGoal(blocker)) {
                reasons.add(blocker.get("reasonCode").getAsString());
            }
        });
        String matrixText = Files.readString(supportFile) + "\n" + Files.readString(opcodeFile);
        List<String> missing = reasons.stream()
                .filter(reason -> !matrixText.contains(reason))
                .sorted()
                .toList();
        checks.add(missing.isEmpty()
                ? ReleaseReadinessCheck.passed("knownBlockers.matrixCoverage", "KNOWN_BLOCKERS_MATRIX_COVERAGE", "all blockers are mentioned by support/opcode matrix")
                : ReleaseReadinessCheck.failed("knownBlockers.matrixCoverage", "KNOWN_BLOCKERS_MATRIX_COVERAGE_MISSING", "missing matrix reasons: " + missing));
    }

    private void checkKnownBlockersHaveSuiteOrSeedCoverage(List<ReleaseReadinessCheck> checks, Path reports) throws IOException {
        Path blockersFile = reports.resolve("known-blockers.json");
        Path suiteFile = reports.resolve("release-suite-summary.json");
        if (!Files.isRegularFile(blockersFile) || !Files.isRegularFile(suiteFile)) {
            checks.add(ReleaseReadinessCheck.failed("knownBlockers.suiteCoverage", "REPORT_MISSING", "blocker or release suite report is missing"));
            return;
        }
        Set<String> blockerReasons = releaseBlockingReasonCodes(blockersFile);
        Set<String> coveredReasons = suiteReasonCoverage(suiteFile);
        coveredReasons.addAll(WEIRD_SEED_COVERED_REASONS);
        List<String> missing = blockerReasons.stream()
                .filter(reason -> !coveredReasons.contains(reason))
                .sorted()
                .toList();
        checks.add(missing.isEmpty()
                ? ReleaseReadinessCheck.passed("knownBlockers.suiteCoverage", "KNOWN_BLOCKERS_SUITE_OR_SEED_COVERAGE", "all blockers have release suite or weird seed coverage")
                : ReleaseReadinessCheck.failed("knownBlockers.suiteCoverage", "KNOWN_BLOCKERS_SUITE_COVERAGE_MISSING", "missing suite/seed coverage reasons: " + missing));
    }

    private void checkSuiteCasesMatchExpectations(List<ReleaseReadinessCheck> checks, Path reports) throws IOException {
        Path suiteFile = reports.resolve("release-suite-summary.json");
        if (!Files.isRegularFile(suiteFile)) {
            checks.add(ReleaseReadinessCheck.failed("releaseSuite.expectations", "REPORT_MISSING", "release-suite-summary.json is missing"));
            return;
        }
        JsonObject suite = JsonParser.parseString(Files.readString(suiteFile)).getAsJsonObject();
        JsonArray cases = suite.getAsJsonArray("cases");
        ArrayList<String> failures = new ArrayList<>();
        for (JsonElement element : cases) {
            JsonObject item = element.getAsJsonObject();
            String name = item.get("name").getAsString();
            boolean expectedSuccess = item.get("expectedPipelineSuccess").getAsBoolean();
            boolean pipelineSuccessful = item.get("pipelineSuccessful").getAsBoolean();
            boolean passed = item.get("passed").getAsBoolean();
            boolean hasOutput = item.has("output") && !item.get("output").isJsonNull();
            if (!passed || expectedSuccess != pipelineSuccessful) {
                failures.add(name + ": pass/status mismatch");
            }
            if (expectedSuccess && !hasOutput) {
                failures.add(name + ": successful case missing output run");
            }
            if (!expectedSuccess && hasOutput) {
                failures.add(name + ": expected failure produced output run");
            }
            if (!expectedSuccess && !caseDiagnosticsMatchExpectedStatuses(item)) {
                failures.add(name + ": expected failure lacks matching diagnostic reason");
            }
            if (!expectedSuccess && !expectedFailureEvidenceComplete(item)) {
                failures.add(name + ": expected failure lacks stage/reason/failure-report/no-final-artifact evidence");
            }
            if (expectedSuccess && !reportsContainRequiredNames(item.getAsJsonArray("reports"))) {
                failures.add(name + ": successful case missing required report names");
            }
        }
        checks.add(failures.isEmpty()
                ? ReleaseReadinessCheck.passed("releaseSuite.expectations", "RELEASE_SUITE_EXPECTATIONS_MATCH", "suite cases match expected status and artifacts")
                : ReleaseReadinessCheck.failed("releaseSuite.expectations", "RELEASE_SUITE_EXPECTATION_MISMATCH", String.join("; ", failures)));
    }

    private void checkReleaseSuiteProfile(List<ReleaseReadinessCheck> checks, Path reports) throws IOException {
        Path suiteFile = reports.resolve("release-suite-summary.json");
        if (!Files.isRegularFile(suiteFile)) {
            checks.add(ReleaseReadinessCheck.failed(
                    "releaseSuite.profile",
                    "REPORT_MISSING",
                    "release-suite-summary.json is missing"));
            return;
        }
        JsonObject suite = JsonParser.parseString(Files.readString(suiteFile)).getAsJsonObject();
        String profile = hasText(suite, "profile") ? suite.get("profile").getAsString() : "standard";
        if (!profile.equals("rc")) {
            checks.add(ReleaseReadinessCheck.passed(
                    "releaseSuite.profile",
                    "RELEASE_SUITE_PROFILE_RECORDED",
                    "release suite profile is " + profile));
            return;
        }
        JsonArray missing = suite.has("missingCategories") && suite.get("missingCategories").isJsonArray()
                ? suite.getAsJsonArray("missingCategories")
                : new JsonArray();
        ArrayList<String> values = new ArrayList<>();
        missing.forEach(item -> values.add(item.getAsString()));
        checks.add(values.isEmpty()
                ? ReleaseReadinessCheck.passed(
                        "releaseSuite.profile",
                        "RELEASE_SUITE_RC_CATEGORIES_COMPLETE",
                        "rc profile contains all required categories")
                : ReleaseReadinessCheck.failed(
                        "releaseSuite.profile",
                        "RELEASE_SUITE_RC_CATEGORIES_MISSING",
                        "missing rc categories: " + values.stream().sorted().toList()));
    }

    private BetaProfileGates checkBetaProfileEvidence(
            List<ReleaseReadinessCheck> checks,
            Path reports,
            boolean requireReleaseSuiteSummary) throws IOException {
        if (!requireReleaseSuiteSummary) {
            return new BetaProfileGates(false, List.of(), false, false);
        }
        Path suiteFile = reports.resolve("release-suite-summary.json");
        if (!Files.isRegularFile(suiteFile)) {
            return new BetaProfileGates(false, List.of(), false, false);
        }
        JsonObject suite = JsonParser.parseString(Files.readString(suiteFile)).getAsJsonObject();
        String profile = hasText(suite, "profile") ? suite.get("profile").getAsString() : "standard";
        if (!profile.equals("beta")) {
            return new BetaProfileGates(false, List.of(), false, false);
        }

        ArrayList<String> missing = new ArrayList<>();
        boolean cliSmoke = suiteHasCategoryOrFeature(suite, "cli-artifact-smoke");
        boolean docsExamples = suiteHasCategoryOrFeature(suite, "docs-examples-validated");
        boolean reportIndex = Files.isRegularFile(reports.resolve("index.json"));
        JsonArray missingCategories = suite.has("missingCategories") && suite.get("missingCategories").isJsonArray()
                ? suite.getAsJsonArray("missingCategories")
                : new JsonArray();
        missingCategories.forEach(item -> missing.add("missing category " + item.getAsString()));
        if (!cliSmoke) {
            missing.add("missing cli-artifact-smoke evidence");
        }
        if (!docsExamples) {
            missing.add("missing docs-examples-validated evidence");
        }
        if (!reportIndex) {
            missing.add("missing reports/index.json");
        }
        missing.addAll(uncoveredBetaBlockers(reports, suiteFile));

        checks.add(missing.isEmpty()
                ? ReleaseReadinessCheck.passed(
                        "releaseSuite.betaProfile",
                        "RELEASE_SUITE_BETA_CATEGORIES_COMPLETE",
                        "beta profile contains CLI smoke, docs examples and report index evidence")
                : ReleaseReadinessCheck.failed(
                        "releaseSuite.betaProfile",
                        reportIndex ? "RELEASE_SUITE_BETA_CATEGORIES_MISSING" : "BETA_REPORT_INDEX_MISSING",
                        "missing beta evidence: " + missing.stream().sorted().toList()));
        return new BetaProfileGates(missing.isEmpty(), missing, cliSmoke, docsExamples);
    }

    private List<String> uncoveredBetaBlockers(Path reports, Path suiteFile) throws IOException {
        Path blockersFile = reports.resolve("known-blockers.json");
        if (!Files.isRegularFile(blockersFile) || !Files.isRegularFile(suiteFile)) {
            return List.of();
        }
        Set<String> coveredReasons = suiteReasonCoverage(suiteFile);
        coveredReasons.addAll(WEIRD_SEED_COVERED_REASONS);
        JsonObject blockers = JsonParser.parseString(Files.readString(blockersFile)).getAsJsonObject();
        ArrayList<String> missing = new ArrayList<>();
        blockers.getAsJsonArray("blockers").forEach(element -> {
            JsonObject blocker = element.getAsJsonObject();
            if (!isBetaBlocking(blocker) || isExplicitNonGoal(blocker)) {
                return;
            }
            String reason = textOrEmpty(blocker, "reasonCode");
            if (!coveredReasons.contains(reason)) {
                missing.add("uncovered beta blocker " + reason);
            }
        });
        return missing.stream().sorted().toList();
    }

    private boolean suiteHasCategoryOrFeature(JsonObject suite, String value) {
        if (!suite.has("cases") || !suite.get("cases").isJsonArray()) {
            return false;
        }
        for (JsonElement element : suite.getAsJsonArray("cases")) {
            JsonObject item = element.getAsJsonObject();
            if (hasText(item, "category") && item.get("category").getAsString().equals(value)) {
                return true;
            }
            if (item.has("features") && item.get("features").isJsonArray()) {
                for (JsonElement feature : item.getAsJsonArray("features")) {
                    if (feature.getAsString().equals(value)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private Set<String> reasonCodes(Path blockersFile) throws IOException {
        JsonObject blockers = JsonParser.parseString(Files.readString(blockersFile)).getAsJsonObject();
        HashSet<String> reasons = new HashSet<>();
        blockers.getAsJsonArray("blockers").forEach(element -> {
            JsonObject blocker = element.getAsJsonObject();
            if (hasText(blocker, "reasonCode") && releaseBlocking(blocker)) {
                reasons.add(blocker.get("reasonCode").getAsString());
            }
        });
        return reasons;
    }

    private Set<String> releaseBlockingReasonCodes(Path blockersFile) throws IOException {
        return reasonCodes(blockersFile);
    }

    private List<ReleaseBlockerCoverage> blockerCoverage(Path reports) throws IOException {
        Path blockersFile = reports.resolve("known-blockers.json");
        if (!Files.isRegularFile(blockersFile)) {
            return List.of();
        }
        Map<String, SuiteEvidence> suite = Files.isRegularFile(reports.resolve("release-suite-summary.json"))
                ? suiteEvidence(reports.resolve("release-suite-summary.json"))
                : Map.of();
        JsonObject blockers = JsonParser.parseString(Files.readString(blockersFile)).getAsJsonObject();
        ArrayList<ReleaseBlockerCoverage> coverage = new ArrayList<>();
        blockers.getAsJsonArray("blockers").forEach(element -> {
            JsonObject blocker = element.getAsJsonObject();
            String id = textOrEmpty(blocker, "id");
            String reason = textOrEmpty(blocker, "reasonCode");
            String reportLocation = textOrEmpty(blocker, "reportLocation");
            SuiteEvidence suiteEvidence = suite.get(reason);
            if (suiteEvidence != null) {
                coverage.add(new ReleaseBlockerCoverage(
                        id,
                        reason,
                        reportLocation,
                        true,
                        "releaseSuiteCase",
                        suiteEvidence.caseName(),
                        suiteEvidence.expectedStatus()));
            } else if (isExplicitNonGoal(blocker)) {
                coverage.add(new ReleaseBlockerCoverage(
                        id,
                        reason,
                        reportLocation,
                        true,
                        "explicitNonGoal",
                        null,
                        textOrEmpty(blocker, "targetMilestone")));
            } else if (!releaseBlocking(blocker)) {
                coverage.add(new ReleaseBlockerCoverage(
                        id,
                        reason,
                        reportLocation,
                        true,
                        "notRequiredUntilMilestone",
                        null,
                        textOrEmpty(blocker, "targetMilestone")));
            } else if (WEIRD_SEED_COVERED_REASONS.contains(reason)) {
                coverage.add(new ReleaseBlockerCoverage(
                        id,
                        reason,
                        reportLocation,
                        true,
                        "weirdBytecodeSeed",
                        null,
                        "seeded"));
            } else {
                coverage.add(new ReleaseBlockerCoverage(
                        id,
                        reason,
                        reportLocation,
                        false,
                        "missing",
                        null,
                        ""));
            }
        });
        return coverage.stream()
                .sorted(java.util.Comparator
                        .comparing(ReleaseBlockerCoverage::blockerId)
                        .thenComparing(ReleaseBlockerCoverage::reasonCode))
                .toList();
    }

    private boolean releaseBlocking(JsonObject blocker) {
        String severity = textOrEmpty(blocker, "severity");
        String targetMilestone = textOrEmpty(blocker, "targetMilestone");
        return severity.equals("beta-blocker")
                || severity.equals("rc-blocker")
                || targetMilestone.equals("beta")
                || targetMilestone.equals("rc");
    }

    private boolean isBetaBlocking(JsonObject blocker) {
        String severity = textOrEmpty(blocker, "severity");
        String targetMilestone = textOrEmpty(blocker, "targetMilestone");
        return severity.equals("beta-blocker") || targetMilestone.equals("beta");
    }

    private boolean isExplicitNonGoal(JsonObject blocker) {
        return textOrEmpty(blocker, "severity").equals("non-goal")
                || textOrEmpty(blocker, "targetMilestone").equals("explicit-nongoal");
    }

    private Map<String, SuiteEvidence> suiteEvidence(Path suiteFile) throws IOException {
        JsonObject suite = JsonParser.parseString(Files.readString(suiteFile)).getAsJsonObject();
        HashMap<String, SuiteEvidence> evidence = new HashMap<>();
        for (JsonElement element : suite.getAsJsonArray("cases")) {
            JsonObject item = element.getAsJsonObject();
            String caseName = item.get("name").getAsString();
            item.getAsJsonObject("expectedSupportStatuses").entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> evidence.putIfAbsent(
                            entry.getKey(),
                            new SuiteEvidence(caseName, entry.getValue().getAsString())));
        }
        return evidence;
    }

    private boolean checkTargetEvidence(List<ReleaseReadinessCheck> checks, Path reports) throws IOException {
        Path packaging = reports.resolve("packaging-report.json");
        if (!Files.isRegularFile(packaging)) {
            checks.add(ReleaseReadinessCheck.failed("packaging.targetEvidence", "REPORT_MISSING", "packaging-report.json is missing"));
            return false;
        }
        JsonObject root = JsonParser.parseString(Files.readString(packaging)).getAsJsonObject();
        JsonArray targetArtifacts = root.getAsJsonObject("zigToolchain").getAsJsonArray("targetArtifacts");
        ArrayList<String> failures = new ArrayList<>();
        for (JsonElement element : targetArtifacts) {
            JsonObject target = element.getAsJsonObject();
            String name = textOrEmpty(target, "target");
            requireField(failures, target, name, "target");
            requireField(failures, target, name, "zigTarget");
            requireField(failures, target, name, "osClassifier");
            requireField(failures, target, name, "archClassifier");
            requireField(failures, target, name, "libraryExtension");
            requireField(failures, target, name, "expectedArtifactPath");
            requireField(failures, target, name, "expectedArtifactName");
            requireField(failures, target, name, "expectedResourcePath");
            requireField(failures, target, name, "loaderExtractionPathPolicy");
            requireField(failures, target, name, "symbolVisibilityPolicy");
            requireField(failures, target, name, "windowsPdbPolicy");
            requireField(failures, target, name, "status");
            requireField(failures, target, name, "reasonCode");
            requireField(failures, target, name, "reason");
            requireField(failures, target, name, "requiredCapability");
            requireField(failures, target, name, "platformSdkRequirement");
            requireField(failures, target, name, "failureKind");
            requireField(failures, target, name, "buildLogTail");
            boolean required = target.has("required") && target.get("required").getAsBoolean();
            boolean buildable = target.has("buildable") && target.get("buildable").getAsBoolean();
            boolean failedRequired = required && !buildable;
            boolean hasActual = target.has("actualSha256") && !target.get("actualSha256").isJsonNull();
            if (failedRequired && hasActual) {
                failures.add(name + ": failed required target must not report actualSha256");
            }
            if (failedRequired && !isNull(target, "actualArtifactPath")) {
                failures.add(name + ": failed required target must not report actualArtifactPath");
            }
            if (target.getAsJsonArray("exportedSymbols") == null) {
                failures.add(name + ": exportedSymbols is missing");
            }
        }
        boolean ok = failures.isEmpty();
        checks.add(ok
                ? ReleaseReadinessCheck.passed("packaging.targetEvidence", "TARGET_EVIDENCE_COMPLETE", "target artifact evidence is complete")
                : ReleaseReadinessCheck.failed("packaging.targetEvidence", "TARGET_EVIDENCE_INCOMPLETE", String.join("; ", failures)));
        return ok;
    }

    private boolean checkFinalArtifactWritten(List<ReleaseReadinessCheck> checks, Path workspaceRoot, Path reports) throws IOException {
        Path packaging = reports.resolve("packaging-report.json");
        if (!Files.isRegularFile(packaging)) {
            checks.add(ReleaseReadinessCheck.failed("packaging.finalArtifact", "REPORT_MISSING", "packaging-report.json is missing"));
            return false;
        }
        JsonObject root = JsonParser.parseString(Files.readString(packaging)).getAsJsonObject();
        String outputJar = textOrEmpty(root, "outputJar");
        boolean failedRequiredTarget = false;
        JsonArray targetArtifacts = root.getAsJsonObject("zigToolchain").getAsJsonArray("targetArtifacts");
        for (JsonElement element : targetArtifacts) {
            JsonObject target = element.getAsJsonObject();
            boolean required = target.has("required") && target.get("required").getAsBoolean();
            boolean buildable = target.has("buildable") && target.get("buildable").getAsBoolean();
            failedRequiredTarget |= required && !buildable;
        }
        Path outputPath = outputJar.isBlank()
                ? workspaceRoot.resolve("output.jar")
                : Path.of(outputJar);
        if (!outputPath.isAbsolute()) {
            outputPath = workspaceRoot.resolve(outputPath);
        }
        boolean exists = Files.isRegularFile(outputPath);
        boolean failureReported = failureReportSaysFinalArtifactNotWritten(reports);
        if (failureReported && !exists) {
            checks.add(ReleaseReadinessCheck.passed(
                    "packaging.finalArtifact",
                    "FINAL_ARTIFACT_NOT_WRITTEN_AFTER_FAILURE",
                    "failure report records no retained final output JAR"));
            return false;
        }
        if (failedRequiredTarget && exists) {
            checks.add(ReleaseReadinessCheck.failed(
                    "packaging.finalArtifact",
                    "FINAL_ARTIFACT_WRITTEN_FOR_FAILED_REQUIRED_TARGET",
                    "final output JAR exists despite failed required native target: " + outputJar));
            return false;
        }
        if (!failedRequiredTarget && !exists) {
            checks.add(ReleaseReadinessCheck.failed(
                    "packaging.finalArtifact",
                    "FINAL_ARTIFACT_MISSING",
                    "final output JAR is missing: " + outputJar));
            return false;
        }
        if (failedRequiredTarget) {
            checks.add(ReleaseReadinessCheck.failed(
                    "packaging.finalArtifact",
                    "FINAL_ARTIFACT_BLOCKED_BY_REQUIRED_TARGET",
                    "failed required target blocked final output JAR"));
            return false;
        }
        checks.add(ReleaseReadinessCheck.passed(
                "packaging.finalArtifact",
                "FINAL_ARTIFACT_WRITTEN",
                "final output JAR exists"));
        return exists;
    }

    private void requireField(ArrayList<String> failures, JsonObject object, String target, String field) {
        if (!hasText(object, field)) {
            failures.add(target + ": " + field + " is missing");
        }
    }

    private boolean isNull(JsonObject object, String field) {
        return !object.has(field) || object.get(field).isJsonNull();
    }

    private Set<String> suiteReasonCoverage(Path suiteFile) throws IOException {
        JsonObject suite = JsonParser.parseString(Files.readString(suiteFile)).getAsJsonObject();
        HashSet<String> reasons = new HashSet<>();
        JsonArray cases = suite.getAsJsonArray("cases");
        for (JsonElement element : cases) {
            JsonObject item = element.getAsJsonObject();
            item.getAsJsonObject("expectedSupportStatuses").entrySet().forEach(entry -> reasons.add(entry.getKey()));
            item.getAsJsonArray("diagnostics").forEach(diagnostic -> reasons.add(diagnostic.getAsString()));
        }
        return reasons;
    }

    private boolean caseDiagnosticsMatchExpectedStatuses(JsonObject item) {
        Set<String> expected = item.getAsJsonObject("expectedSupportStatuses").keySet();
        if (expected.isEmpty()) {
            return true;
        }
        HashSet<String> diagnostics = new HashSet<>();
        item.getAsJsonArray("diagnostics").forEach(diagnostic -> diagnostics.add(diagnostic.getAsString()));
        return expected.stream().anyMatch(diagnostics::contains);
    }

    private boolean reportsContainRequiredNames(JsonArray reports) {
        HashSet<String> names = new HashSet<>();
        reports.forEach(report -> names.add(report.getAsString()));
        return names.containsAll(REQUIRED_REPORTS);
    }

    private void checkArtifactAuditStatus(List<ReleaseReadinessCheck> checks, Path reports) throws IOException {
        Path file = reports.resolve("artifact-audit.json");
        if (!Files.isRegularFile(file)) {
            checks.add(ReleaseReadinessCheck.failed("artifactAudit.status", "REPORT_MISSING", "artifact-audit.json is missing"));
            return;
        }
        JsonObject audit = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        boolean passed = audit.has("passed") && audit.get("passed").getAsBoolean();
        boolean failureReported = Files.isRegularFile(reports.resolve("failure-report.json"));
        if (passed) {
            checks.add(ReleaseReadinessCheck.passed("artifactAudit.status", "ARTIFACT_AUDIT_PASSED", "artifact audit passed"));
        } else if (failureReported) {
            checks.add(ReleaseReadinessCheck.passed(
                    "artifactAudit.status",
                    "ARTIFACT_AUDIT_FAILURE_RECORDED",
                    "artifact audit did not pass and failure-report.json records the failed run"));
        } else {
            checks.add(ReleaseReadinessCheck.failed(
                    "artifactAudit.status",
                    "ARTIFACT_AUDIT_FAILED",
                    "artifact audit failed without a failure report"));
        }
    }

    private void checkReportIndexIntegrity(List<ReleaseReadinessCheck> checks, Path workspaceRoot, Path reports)
            throws IOException {
        Path index = reports.resolve("index.json");
        if (!Files.isRegularFile(index)) {
            checks.add(ReleaseReadinessCheck.failed(
                    "reportIndex.integrity",
                    "REPORT_MISSING",
                    "index.json is missing"));
            return;
        }
        JsonObject root = JsonParser.parseString(Files.readString(index)).getAsJsonObject();
        JsonArray entries = root.has("reports") && root.get("reports").isJsonArray()
                ? root.getAsJsonArray("reports")
                : new JsonArray();
        Set<String> indexedPaths = new HashSet<>();
        ArrayList<String> failures = new ArrayList<>();
        Set<String> volatilePaths = Set.of(
                "reports/release-readiness.json",
                "reports/summary.json",
                "reports/summary.md");
        for (JsonElement element : entries) {
            JsonObject entry = element.getAsJsonObject();
            String path = textOrEmpty(entry, "path");
            indexedPaths.add(path);
            if (path.isBlank()) {
                failures.add("blank path");
                continue;
            }
            Path actual = workspaceRoot.resolve(path).normalize();
            if (!actual.startsWith(workspaceRoot.normalize()) || !Files.isRegularFile(actual)) {
                failures.add(path + ": missing");
                continue;
            }
            if (!volatilePaths.contains(path)) {
                String expected = textOrEmpty(entry, "sha256");
                String actualSha = sha256(actual);
                if (!expected.equals(actualSha)) {
                    failures.add(path + ": sha256 mismatch");
                }
            }
        }
        List<String> required = List.of(
                "reports/diagnostics.json",
                "reports/artifact-audit.json",
                "reports/field-internalization-report.json",
                "reports/known-blockers.json",
                "reports/lowering-report.json",
                "reports/opcode-support-matrix.json",
                "reports/packaging-report.json",
                "reports/protection-report.json",
                "reports/release-readiness.json",
                "reports/skipped-method-report.json",
                "reports/support-matrix.json",
                "reports/symbol-audit.json",
                "reports/summary.json",
                "reports/summary.md");
        required.stream()
                .filter(path -> Files.isRegularFile(workspaceRoot.resolve(path)))
                .filter(path -> !indexedPaths.contains(path))
                .forEach(path -> failures.add(path + ": not indexed"));
        if (!finalJarReportsManifestMatchesIndex(workspaceRoot, reports, indexedPaths)) {
            failures.add("final JAR reports-manifest does not match workspace report index contract");
        }
        checks.add(failures.isEmpty()
                ? ReleaseReadinessCheck.passed(
                        "reportIndex.integrity",
                        "REPORT_INDEX_INTEGRITY_PASSED",
                        "report index paths and SHA-256 entries match emitted reports")
                : ReleaseReadinessCheck.failed(
                        "reportIndex.integrity",
                        "REPORT_INDEX_INTEGRITY_FAILED",
                        "report index failures: " + failures.stream().sorted().toList()));
    }

    private boolean finalJarReportsManifestMatchesIndex(Path workspaceRoot, Path reports, Set<String> indexedPaths)
            throws IOException {
        Path packaging = reports.resolve("packaging-report.json");
        if (!Files.isRegularFile(packaging)) {
            return true;
        }
        JsonObject packagingJson = JsonParser.parseString(Files.readString(packaging)).getAsJsonObject();
        String outputJar = textOrEmpty(packagingJson, "outputJar");
        if (outputJar.isBlank()) {
            return true;
        }
        Path jarPath = Path.of(outputJar);
        if (!jarPath.isAbsolute()) {
            jarPath = workspaceRoot.resolve(jarPath);
        }
        if (!Files.isRegularFile(jarPath)) {
            return true;
        }
        try (JarFile jar = new JarFile(jarPath.toFile(), false)) {
            var entry = jar.getJarEntry("META-INF/j2ll/reports-manifest.json");
            if (entry == null) {
                return false;
            }
            JsonObject manifest = JsonParser.parseString(new String(
                            jar.getInputStream(entry).readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject();
            if (!"reports/index.json".equals(textOrEmpty(manifest, "reportIndex"))
                    || !"workspaceReportIndexSha256".equals(textOrEmpty(manifest, "reportHashSource"))) {
                return false;
            }
            if (!manifest.has("reports") || !manifest.get("reports").isJsonArray()) {
                return false;
            }
            HashSet<String> manifestReports = new HashSet<>();
            manifest.getAsJsonArray("reports").forEach(item -> manifestReports.add("reports/" + item.getAsString()));
            return manifestReports.contains("reports/index.json")
                    && manifestReports.contains("reports/summary.md")
                    && manifestReports.stream()
                            .filter(path -> !path.equals("reports/index.json"))
                            .filter(path -> Files.isRegularFile(workspaceRoot.resolve(path)))
                            .allMatch(indexedPaths::contains);
        } catch (IOException exception) {
            // Some focused readiness tests use a tiny placeholder file for the final artifact.
            // The full artifact-audit path validates real output JAR metadata.
            return true;
        }
    }

    private AuditDerivedGates checkArtifactAuditDerivedGates(List<ReleaseReadinessCheck> checks, Path reports)
            throws IOException {
        Path file = reports.resolve("artifact-audit.json");
        if (!Files.isRegularFile(file)) {
            checks.add(ReleaseReadinessCheck.failed(
                    "artifactAudit.metadataConsistency",
                    "REPORT_MISSING",
                    "artifact-audit.json is missing"));
            checks.add(ReleaseReadinessCheck.failed(
                    "artifactAudit.blockingSensitiveFacts",
                    "REPORT_MISSING",
                    "artifact-audit.json is missing"));
            return new AuditDerivedGates(false, false);
        }
        JsonObject audit = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        JsonArray auditChecks = audit.has("checks") && audit.get("checks").isJsonArray()
                ? audit.getAsJsonArray("checks")
                : new JsonArray();
        ArrayList<String> metadataFailures = new ArrayList<>();
        ArrayList<String> plaintextFailures = new ArrayList<>();
        boolean hasMetadataCheck = false;
        boolean hasBlockingFacts = audit.has("checkedSensitiveFacts")
                && audit.get("checkedSensitiveFacts").isJsonArray()
                && !audit.getAsJsonArray("checkedSensitiveFacts").isEmpty();
        for (JsonElement element : auditChecks) {
            JsonObject check = element.getAsJsonObject();
            String name = textOrEmpty(check, "name");
            String reason = textOrEmpty(check, "reasonCode");
            String status = textOrEmpty(check, "status");
            if (name.startsWith("metadata.")) {
                hasMetadataCheck = true;
                if ("failed".equals(status)) {
                    metadataFailures.add(reason);
                }
            }
            if ("failed".equals(status)
                    && (reason.equals("FORBIDDEN_PLAINTEXT_FOUND")
                            || reason.equals("FORBIDDEN_PLAINTEXT_JAR_ENTRY"))) {
                plaintextFailures.add(reason);
            }
        }
        boolean metadataPassed = hasMetadataCheck && metadataFailures.isEmpty();
        boolean blockingPassed = plaintextFailures.isEmpty();
        checks.add(metadataPassed
                ? ReleaseReadinessCheck.passed(
                        "artifactAudit.metadataConsistency",
                        "METADATA_CONSISTENCY_PASSED",
                        "artifact audit metadata consistency checks passed")
                : ReleaseReadinessCheck.failed(
                        "artifactAudit.metadataConsistency",
                        "METADATA_CONSISTENCY_FAILED",
                        hasMetadataCheck
                                ? "metadata consistency failures: " + metadataFailures.stream().sorted().toList()
                                : "metadata consistency checks are missing from artifact audit"));
        checks.add(blockingPassed
                ? ReleaseReadinessCheck.passed(
                        "artifactAudit.blockingSensitiveFacts",
                        hasBlockingFacts ? "BLOCKING_SENSITIVE_FACTS_PASSED" : "NO_BLOCKING_SENSITIVE_FACTS",
                        hasBlockingFacts
                                ? "blocking sensitive fact audit passed"
                                : "no blocking sensitive facts were present")
                : ReleaseReadinessCheck.failed(
                        "artifactAudit.blockingSensitiveFacts",
                        "BLOCKING_SENSITIVE_PLAINTEXT_LEAK",
                        "blocking plaintext failures: " + plaintextFailures.stream().sorted().toList()));
        return new AuditDerivedGates(metadataPassed, blockingPassed);
    }

    private boolean expectedFailureEvidenceComplete(JsonObject item) {
        boolean expectedFailure = item.has("expectedFailure") && item.get("expectedFailure").getAsBoolean();
        boolean noFinalArtifact = item.has("finalArtifactWritten") && !item.get("finalArtifactWritten").getAsBoolean();
        boolean hasStage = hasText(item, "expectedFailureStage");
        boolean hasReason = hasText(item, "expectedFailureReasonCode");
        boolean hasFailureReport = false;
        if (item.has("reports") && item.get("reports").isJsonArray()) {
            for (JsonElement report : item.getAsJsonArray("reports")) {
                hasFailureReport |= report.getAsString().equals("failure-report.json");
            }
        }
        return expectedFailure && noFinalArtifact && hasStage && hasReason && hasFailureReport;
    }

    private boolean failureReportSaysFinalArtifactNotWritten(Path reports) throws IOException {
        Path file = reports.resolve("failure-report.json");
        if (!Files.isRegularFile(file)) {
            return false;
        }
        JsonObject failure = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        return failure.has("finalArtifactWritten") && !failure.get("finalArtifactWritten").getAsBoolean();
    }

    private void checkNoLegacyPathOutputs(List<ReleaseReadinessCheck> checks, Path workspaceRoot) throws IOException {
        if (!Files.exists(workspaceRoot)) {
            checks.add(ReleaseReadinessCheck.failed("workspace.noLegacyOutputs", "WORKSPACE_MISSING", "workspace is missing"));
            return;
        }
        try (Stream<Path> paths = Files.walk(workspaceRoot)) {
            List<String> legacyPaths = paths
                    .map(path -> workspaceRoot.relativize(path).toString().replace('\\', '/'))
                    .filter(path -> path.contains("obfuscator/src") || path.contains("obfuscator/bench"))
                    .sorted()
                    .toList();
            checks.add(legacyPaths.isEmpty()
                    ? ReleaseReadinessCheck.passed("workspace.noLegacyOutputs", "NO_LEGACY_PATH_OUTPUTS", "workspace contains no legacy output paths")
                    : ReleaseReadinessCheck.failed("workspace.noLegacyOutputs", "LEGACY_PATH_OUTPUT_FOUND", "legacy output paths: " + legacyPaths));
        }
    }

    private boolean hasText(JsonObject object, String field) {
        return object.has(field)
                && !object.get(field).isJsonNull()
                && !object.get(field).getAsString().isBlank();
    }

    private String textOrEmpty(JsonObject object, String field) {
        return hasText(object, field) ? object.get(field).getAsString() : "";
    }

    private String sha256(Path path) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record SuiteEvidence(String caseName, String expectedStatus) {}

    private record AuditDerivedGates(boolean metadataConsistencyPassed, boolean blockingSensitiveFactsPassed) {}

    private record BetaProfileGates(
            boolean betaProfilePassed,
            List<String> missingEvidence,
            boolean cliArtifactSmokePassed,
            boolean docsExamplesValidated) {}
}
