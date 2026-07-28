package xyz.melodysky.protection.audit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Map;

/** Stable hash-only JSON writer for protection coverage and dual-build diff. */
public final class ProtectionCoverageReportWriter {
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    public String json(ProtectionCoverageSnapshot snapshot) {
        JsonObject root = header();
        root.addProperty("evaluatedFacts", snapshot.evaluatedFacts());
        root.addProperty("requestedFacts", snapshot.requestedFacts());
        root.addProperty("applicableFacts", snapshot.applicableFacts());
        root.addProperty(
                "notApplicableFacts",
                snapshot.notApplicableFacts());
        root.addProperty(
                "unknownApplicabilityFacts",
                snapshot.unknownApplicabilityFacts());
        root.addProperty("affectedFacts", snapshot.affectedFacts());
        root.addProperty(
                "affectedRateBasisPoints",
                snapshot.affectedRateBasisPoints());
        JsonArray passes = new JsonArray();
        snapshot.passes().forEach(pass -> passes.add(pass(pass)));
        root.add("passes", passes);
        JsonArray facts = new JsonArray();
        snapshot.facts().forEach(fact -> facts.add(fact(fact)));
        root.add("facts", facts);
        return GSON.toJson(root) + "\n";
    }

    public String diffJson(ProtectionCoverageDiffMetric metric) {
        JsonObject root = header();
        root.addProperty("firstFactCount", metric.firstFactCount());
        root.addProperty("secondFactCount", metric.secondFactCount());
        root.addProperty("commonFactCount", metric.commonFactCount());
        root.addProperty("addedFactCount", metric.addedFactCount());
        root.addProperty("removedFactCount", metric.removedFactCount());
        root.addProperty(
                "requestedChangedCount",
                metric.requestedChangedCount());
        root.addProperty(
                "applicabilityChangedCount",
                metric.applicabilityChangedCount());
        root.addProperty(
                "affectedChangedCount",
                metric.affectedChangedCount());
        root.addProperty("statusChangedCount", metric.statusChangedCount());
        root.addProperty("reasonChangedCount", metric.reasonChangedCount());
        root.addProperty("firstAffectedFacts", metric.firstAffectedFacts());
        root.addProperty("secondAffectedFacts", metric.secondAffectedFacts());
        root.addProperty("affectedDelta", metric.affectedDelta());
        JsonArray passes = new JsonArray();
        metric.passes().forEach(row -> {
            JsonObject object = new JsonObject();
            object.addProperty("layer", row.layer());
            object.addProperty("passName", row.passName());
            object.addProperty(
                    "firstAffectedSubjects",
                    row.firstAffectedSubjects());
            object.addProperty(
                    "secondAffectedSubjects",
                    row.secondAffectedSubjects());
            object.addProperty("affectedDelta", row.affectedDelta());
            object.addProperty("commonSubjects", row.commonSubjects());
            object.addProperty("addedSubjects", row.addedSubjects());
            object.addProperty("removedSubjects", row.removedSubjects());
            object.addProperty(
                    "requestedChangedSubjects",
                    row.requestedChangedSubjects());
            object.addProperty(
                    "applicabilityChangedSubjects",
                    row.applicabilityChangedSubjects());
            object.addProperty(
                    "affectedChangedSubjects",
                    row.affectedChangedSubjects());
            object.addProperty(
                    "statusChangedSubjects",
                    row.statusChangedSubjects());
            object.addProperty(
                    "reasonChangedSubjects",
                    row.reasonChangedSubjects());
            passes.add(object);
        });
        root.add("passes", passes);
        root.addProperty("reasonCode", metric.reasonCode());
        return GSON.toJson(root) + "\n";
    }

    private JsonObject pass(ProtectionPassCoverageRow pass) {
        JsonObject object = new JsonObject();
        object.addProperty("layer", pass.layer());
        object.addProperty("passName", pass.passName());
        object.addProperty("evaluatedSubjects", pass.evaluatedSubjects());
        object.addProperty("requestedSubjects", pass.requestedSubjects());
        object.addProperty("applicableSubjects", pass.applicableSubjects());
        object.addProperty(
                "notApplicableSubjects",
                pass.notApplicableSubjects());
        object.addProperty(
                "unknownApplicabilitySubjects",
                pass.unknownApplicabilitySubjects());
        object.addProperty("affectedSubjects", pass.affectedSubjects());
        object.addProperty(
                "affectedRateBasisPoints",
                pass.affectedRateBasisPoints());
        object.add("statusCounts", counts(pass.statusCounts()));
        object.add("reasonCounts", counts(pass.reasonCounts()));
        return object;
    }

    private JsonObject fact(ProtectionPassCoverageFact fact) {
        JsonObject object = new JsonObject();
        object.addProperty("layer", fact.layer());
        object.addProperty("passName", fact.passName());
        object.addProperty(
                "subjectIdentityHash",
                fact.subjectIdentityHash());
        object.addProperty("requested", fact.requested());
        object.addProperty(
                "applicability",
                fact.applicability().wireName());
        object.addProperty("affected", fact.affected());
        object.addProperty("status", fact.status());
        object.addProperty("reasonCode", fact.reasonCode());
        return object;
    }

    private JsonObject counts(Map<String, Integer> values) {
        JsonObject object = new JsonObject();
        values.forEach(object::addProperty);
        return object;
    }

    private JsonObject header() {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("reportVersion", 1);
        return root;
    }
}
