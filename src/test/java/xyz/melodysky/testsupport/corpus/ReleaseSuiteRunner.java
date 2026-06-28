package xyz.melodysky.testsupport.corpus;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import xyz.melodysky.report.KnownBlockerEntry;
import xyz.melodysky.report.KnownBlockersWriter;

public final class ReleaseSuiteRunner {
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    public ReleaseSuiteResult run(ReleaseSuite suite, Path root) throws Exception {
        ArrayList<CorpusRunResult> results = new ArrayList<>();
        CorpusRunner runner = new CorpusRunner();
        for (CorpusCase corpusCase : suite.cases()) {
            results.add(runner.run(corpusCase, root.resolve("cases").resolve(corpusCase.name())));
        }
        Path summary = root.resolve("reports").resolve("release-suite-summary.json");
        writeSummary(suite, results, summary);
        for (CorpusRunResult result : results) {
            Path caseSummary = result.reportPaths().reports().values().iterator().next()
                    .getParent()
                    .resolve("release-suite-summary.json");
            Files.createDirectories(caseSummary.getParent());
            Files.copy(summary, caseSummary, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return new ReleaseSuiteResult(suite.name(), results, summary);
    }

    private void writeSummary(ReleaseSuite suite, List<CorpusRunResult> results, Path path) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, json(suite, results));
    }

    public String json(ReleaseSuite suite, List<CorpusRunResult> results) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("reportVersion", 1);
        root.addProperty("suiteName", suite.name());
        root.addProperty("profile", suite.profile().wireName());
        root.add("requiredCategories", stringArray(suite.profile().requiredCategories()));
        root.add("missingCategories", stringArray(missingCategories(suite, results)));
        root.addProperty("caseCount", results.size());
        root.addProperty("passed", results.stream().allMatch(this::passed));
        root.add("aggregate", aggregate(results));
        root.addProperty("determinismEvidenceComplete", determinismEvidenceComplete(results));
        JsonArray cases = new JsonArray();
        results.stream()
                .sorted(Comparator.comparing(result -> result.corpusCase().name()))
                .forEach(result -> cases.add(caseJson(result)));
        root.add("cases", cases);
        return GSON.toJson(root) + "\n";
    }

    private List<String> missingCategories(ReleaseSuite suite, List<CorpusRunResult> results) {
        Set<String> present = new LinkedHashSet<>();
        results.forEach(result -> {
            CorpusCase corpusCase = result.corpusCase();
            if (corpusCase.category() != null && !corpusCase.category().isBlank()) {
                present.add(corpusCase.category());
            }
            corpusCase.features().stream()
                    .filter(feature -> feature != null && !feature.isBlank())
                    .forEach(present::add);
        });
        return suite.profile().requiredCategories().stream()
                .filter(category -> !present.contains(category))
                .sorted()
                .toList();
    }

    private JsonObject aggregate(List<CorpusRunResult> results) {
        JsonObject object = new JsonObject();
        object.addProperty("totalCases", results.size());
        object.addProperty("successCases", results.stream()
                .filter(result -> result.pipelineResult().successful())
                .count());
        object.addProperty("expectedFailureCases", results.stream()
                .filter(result -> !result.corpusCase().expectedPipelineSuccess())
                .count());
        object.add("casesByCategory", countMap(results.stream()
                .map(result -> result.corpusCase().category())
                .toList()));
        object.add("casesByFeature", countMap(results.stream()
                .flatMap(result -> result.corpusCase().features().stream())
                .toList()));
        object.addProperty("strictEvidenceComplete", strictEvidenceComplete(results));
        object.addProperty("determinismEvidenceComplete", determinismEvidenceComplete(results));
        return object;
    }

    private JsonObject countMap(List<String> values) {
        JsonObject object = new JsonObject();
        values.stream()
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.groupingBy(value -> value, java.util.TreeMap::new, Collectors.counting()))
                .forEach(object::addProperty);
        return object;
    }

    private boolean strictEvidenceComplete(List<CorpusRunResult> results) {
        return results.stream().allMatch(result -> {
            if (!passed(result)) {
                return false;
            }
            if (!result.corpusCase().expectedPipelineSuccess()) {
                return !finalArtifactWritten(result)
                        && result.reportPaths().reports().containsKey("failure-report.json")
                        && result.corpusCase().expectedFailureStage() != null
                        && expectedFailureReasonCode(result) != null;
            }
            return result.reportPaths().reports().keySet().containsAll(List.of(
                    "diagnostics.json",
                    "artifact-audit.json",
                    "lowering-report.json",
                    "packaging-report.json",
                    "release-readiness.json"))
                    && (!result.corpusCase().runChildDifferential() || result.normalizedOutputMatches());
        });
    }

    private boolean determinismEvidenceComplete(List<CorpusRunResult> results) {
        List<String> names = results.stream().map(result -> result.corpusCase().name()).sorted().toList();
        boolean stableCaseOrdering = names.equals(results.stream()
                .sorted(Comparator.comparing(result -> result.corpusCase().name()))
                .map(result -> result.corpusCase().name())
                .toList());
        boolean stableMetadata = results.stream().allMatch(result -> result.reportPaths().reports().keySet().stream()
                .sorted()
                .toList()
                .equals(result.reportPaths().reports().keySet().stream().toList()));
        return stableCaseOrdering && stableMetadata;
    }

    private boolean passed(CorpusRunResult result) {
        return result.pipelineResult().successful() == result.corpusCase().expectedPipelineSuccess()
                && (!result.corpusCase().expectedPipelineSuccess()
                        || !result.corpusCase().runChildDifferential()
                        || result.normalizedOutputMatches());
    }

    private JsonObject caseJson(CorpusRunResult result) {
        CorpusCase corpusCase = result.corpusCase();
        JsonObject object = new JsonObject();
        object.addProperty("name", corpusCase.name());
        object.addProperty("category", corpusCase.category());
        object.add("features", stringArray(corpusCase.features()));
        object.add("expectedSupportStatuses", stringMap(corpusCase.expectedSupportStatuses()));
        object.add("expectedSupportEvidence", expectedSupportEvidence(corpusCase.expectedSupportStatuses()));
        object.addProperty("protectionEnabled", corpusCase.protectionEnabled());
        object.addProperty("signaturePolicy", corpusCase.signaturePolicy());
        object.addProperty("expectedPipelineSuccess", corpusCase.expectedPipelineSuccess());
        object.addProperty("expectedFailure", !corpusCase.expectedPipelineSuccess());
        nullableString(object, "expectedFailureStage", corpusCase.expectedFailureStage());
        nullableString(object, "expectedFailureReasonCode", expectedFailureReasonCode(result));
        object.addProperty("pipelineSuccessful", result.pipelineResult().successful());
        object.addProperty("finalArtifactWritten", finalArtifactWritten(result));
        object.addProperty("passed", passed(result));
        object.add("original", result.originalRun() == null ? JsonNull.INSTANCE : runJson(result.originalRun()));
        object.add("output", result.outputRun() == null ? JsonNull.INSTANCE : runJson(result.outputRun()));
        object.add("reports", stringArray(result.reportPaths().reports().keySet().stream().sorted().toList()));
        object.add("diagnostics", stringArray(result.pipelineResult().diagnostics().stream()
                .map(diagnostic -> diagnostic.code().value())
                .sorted()
                .toList()));
        return object;
    }

    private void nullableString(JsonObject object, String field, String value) {
        if (value == null) {
            object.add(field, JsonNull.INSTANCE);
        } else {
            object.addProperty(field, value);
        }
    }

    private String expectedFailureReasonCode(CorpusRunResult result) {
        if (result.corpusCase().expectedFailureReasonCode() != null) {
            return result.corpusCase().expectedFailureReasonCode();
        }
        if (result.corpusCase().expectedPipelineSuccess()) {
            return null;
        }
        return result.pipelineResult().diagnostics().stream()
                .filter(diagnostic -> diagnostic.severity().wireName().equals("error"))
                .map(diagnostic -> diagnostic.code().value())
                .sorted()
                .findFirst()
                .orElse(null);
    }

    private boolean finalArtifactWritten(CorpusRunResult result) {
        return Files.isRegularFile(result.pipelineResult().outputJar());
    }

    private JsonObject runJson(xyz.melodysky.testsupport.JvmRunResult run) {
        JsonObject object = new JsonObject();
        object.addProperty("exitCode", run.exitCode());
        object.addProperty("stdout", run.stdout().replace("\r\n", "\n").replace('\r', '\n').trim());
        object.addProperty("stderr", run.stderr().replace("\r\n", "\n").replace('\r', '\n').trim());
        return object;
    }

    private JsonArray stringArray(List<String> values) {
        JsonArray array = new JsonArray();
        values.stream().sorted().forEach(array::add);
        return array;
    }

    private JsonObject stringMap(Map<String, String> values) {
        JsonObject object = new JsonObject();
        values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> object.addProperty(entry.getKey(), entry.getValue()));
        return object;
    }

    private JsonArray expectedSupportEvidence(Map<String, String> values) {
        Map<String, String> blockerLocations = new KnownBlockersWriter().defaultEntries().stream()
                .collect(Collectors.toMap(KnownBlockerEntry::reasonCode, KnownBlockerEntry::reportLocation));
        JsonArray array = new JsonArray();
        values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    JsonObject object = new JsonObject();
                    object.addProperty("reasonCode", entry.getKey());
                    object.addProperty("expectedStatus", entry.getValue());
                    object.addProperty("reportLocation", blockerLocations.getOrDefault(
                            entry.getKey(),
                            defaultReportLocation(entry.getKey())));
                    array.add(object);
                });
        return array;
    }

    private String defaultReportLocation(String reasonCode) {
        if (reasonCode.startsWith("SIGNATURE_")
                || reasonCode.equals("baseClassesOnlyPreserveVersionedEntries")) {
            return "reports/packaging-report.json";
        }
        if (reasonCode.equals("LLVM_NATIVE_PATH")
                || reasonCode.startsWith("PROTECTION_")
                || reasonCode.equals("CONTROL_FLOW_FLATTENING")) {
            return "reports/protection-report.json";
        }
        return "reports/lowering-report.json";
    }
}
