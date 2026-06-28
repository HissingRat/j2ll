package xyz.melodysky.report;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public final class ArtifactAuditReportWriter {
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    public String json(ArtifactAuditResult result) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("reportVersion", 1);
        root.addProperty("passed", result.passed());
        root.add("checkedSensitiveFacts", facts(result.checkedSensitiveFacts()));
        root.add("observedOnlySensitiveFacts", facts(result.observedOnlySensitiveFacts()));
        root.add("skippedSensitiveFacts", facts(result.skippedSensitiveFacts()));
        JsonArray checks = new JsonArray();
        result.checks().stream()
                .sorted(java.util.Comparator
                        .comparing(ArtifactAuditCheck::name)
                        .thenComparing(ArtifactAuditCheck::reasonCode))
                .forEach(check -> {
                    JsonObject object = new JsonObject();
                    object.addProperty("name", check.name());
                    object.addProperty("status", check.status());
                    object.addProperty("reasonCode", check.reasonCode());
                    object.addProperty("message", check.message());
                    checks.add(object);
                });
        root.add("checks", checks);
        return GSON.toJson(root) + "\n";
    }

    private JsonArray facts(java.util.List<SensitivePlaintextFact> facts) {
        JsonArray array = new JsonArray();
        facts.stream()
                .sorted(java.util.Comparator
                        .comparing(SensitivePlaintextFact::literalHash)
                        .thenComparing(SensitivePlaintextFact::sourceMethod)
                        .thenComparing(SensitivePlaintextFact::pathKind)
                        .thenComparing(SensitivePlaintextFact::gateMode)
                        .thenComparing(SensitivePlaintextFact::promotionReason))
                .forEach(fact -> {
                    JsonObject object = new JsonObject();
                    object.addProperty("literalHash", fact.literalHash());
                    object.addProperty("sourceMethod", fact.sourceMethod());
                    object.addProperty("passName", fact.passName());
                    object.addProperty("pathKind", fact.pathKind());
                    object.addProperty("gateMode", fact.gateMode());
                    object.addProperty("sourceSurface", fact.sourceSurface());
                    object.addProperty("reason", fact.reason());
                    object.addProperty("promotionReason", fact.promotionReason());
                    array.add(object);
                });
        return array;
    }
}
