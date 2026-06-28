package xyz.melodysky.report;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Comparator;

public final class ReleaseReadinessWriter {
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    public String json(ReleaseReadinessResult result) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("reportVersion", 1);
        root.addProperty("status", result.passed() ? "passed" : "failed");
        root.addProperty("blockerEvidenceComplete", result.blockerEvidenceComplete());
        root.addProperty("targetEvidenceComplete", result.targetEvidenceComplete());
        root.addProperty("finalArtifactWritten", result.finalArtifactWritten());
        root.addProperty("determinismEvidenceComplete", result.determinismEvidenceComplete());
        root.addProperty("metadataConsistencyPassed", result.metadataConsistencyPassed());
        root.addProperty("blockingSensitiveFactsPassed", result.blockingSensitiveFactsPassed());
        root.addProperty("targetPackagePlanComplete", result.targetPackagePlanComplete());
        root.addProperty("betaProfilePassed", result.betaProfilePassed());
        root.add("betaMissingEvidence", stringArray(result.betaMissingEvidence()));
        root.addProperty("cliArtifactSmokePassed", result.cliArtifactSmokePassed());
        root.addProperty("docsExamplesValidated", result.docsExamplesValidated());
        root.addProperty("strictModePassed", result.strictModePassed());
        JsonArray missingEvidence = new JsonArray();
        result.missingEvidence().stream()
                .sorted(Comparator
                        .comparing(ReleaseReadinessMissingEvidence::type)
                        .thenComparing(ReleaseReadinessMissingEvidence::name)
                        .thenComparing(ReleaseReadinessMissingEvidence::reasonCode))
                .forEach(item -> {
                    JsonObject object = new JsonObject();
                    object.addProperty("type", item.type());
                    object.addProperty("name", item.name());
                    object.addProperty("reasonCode", item.reasonCode());
                    object.addProperty("detail", item.detail());
                    object.addProperty("reportPath", item.reportPath());
                    missingEvidence.add(object);
                });
        root.add("missingEvidence", missingEvidence);
        JsonArray coverage = new JsonArray();
        result.suiteCoverageByBlocker().stream()
                .sorted(Comparator
                        .comparing(ReleaseBlockerCoverage::blockerId)
                        .thenComparing(ReleaseBlockerCoverage::reasonCode))
                .forEach(item -> {
                    JsonObject object = new JsonObject();
                    object.addProperty("blockerId", item.blockerId());
                    object.addProperty("reasonCode", item.reasonCode());
                    object.addProperty("reportLocation", item.reportLocation());
                    object.addProperty("covered", item.covered());
                    object.addProperty("evidenceType", item.evidenceType());
                    if (item.caseName() == null) {
                        object.add("caseName", com.google.gson.JsonNull.INSTANCE);
                    } else {
                        object.addProperty("caseName", item.caseName());
                    }
                    object.addProperty("expectedStatus", item.expectedStatus());
                    coverage.add(object);
                });
        root.add("suiteCoverageByBlocker", coverage);
        JsonArray checks = new JsonArray();
        result.checks().stream()
                .sorted(Comparator.comparing(ReleaseReadinessCheck::name))
                .forEach(check -> {
                    JsonObject object = new JsonObject();
                    object.addProperty("name", check.name());
                    object.addProperty("status", check.status());
                    object.addProperty("reasonCode", check.reasonCode());
                    object.addProperty("detail", check.detail());
                    checks.add(object);
                });
        root.add("checks", checks);
        return GSON.toJson(root) + "\n";
    }

    private JsonArray stringArray(java.util.List<String> values) {
        JsonArray array = new JsonArray();
        values.stream().sorted().forEach(array::add);
        return array;
    }
}
