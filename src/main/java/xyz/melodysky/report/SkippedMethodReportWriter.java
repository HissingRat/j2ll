package xyz.melodysky.report;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import xyz.melodysky.ir.ssa.SsaMethodResult;
import xyz.melodysky.pipeline.SkippedMethodCollector;
import xyz.melodysky.pipeline.SkippedMethodGateDecision;
import xyz.melodysky.pipeline.SkippedMethodGateEvidence;

/** Writes the stable report for selected Code-bearing methods whose original bodies were preserved. */
public final class SkippedMethodReportWriter {
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls()
            .setPrettyPrinting()
            .create();

    public String json(
            List<SsaMethodResult> results,
            SkippedMethodGateDecision gateDecision) {
        return json(new SkippedMethodGateEvidence(
                new SkippedMethodCollector().collect(results),
                gateDecision));
    }

    public String json(SkippedMethodGateEvidence evidence) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("reportVersion", 1);
        root.addProperty(
                "confirmationRequired",
                !evidence.methods().isEmpty());
        root.addProperty(
                "confirmationDecision",
                evidence.decision().wireName());
        JsonArray entries = new JsonArray();
        evidence.methods().forEach(method -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("selector", method.methodKey());
            entry.addProperty("class", method.owner());
            entry.addProperty("method", method.name());
            entry.addProperty("descriptor", method.descriptor());
            entry.addProperty("status", "skipped");
            entry.addProperty("hasCode", true);
            entry.addProperty("stage", method.stage().name());
            entry.addProperty("reasonCode", method.reasonCode());
            entry.addProperty("reason", method.reason());
            entry.addProperty("affectsCallers", true);
            entries.add(entry);
        });
        root.add("entries", entries);
        return GSON.toJson(root) + "\n";
    }
}
