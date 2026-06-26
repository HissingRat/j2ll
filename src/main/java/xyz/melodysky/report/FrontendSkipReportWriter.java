package xyz.melodysky.report;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Comparator;
import java.util.List;
import xyz.melodysky.ir.ssa.SsaMethodResult;
import xyz.melodysky.pipeline.LoweringStatus;

public final class FrontendSkipReportWriter {
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls()
            .setPrettyPrinting()
            .create();

    public String json(List<SsaMethodResult> results) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        JsonArray entries = new JsonArray();
        results.stream()
                .filter(result -> result.status() == LoweringStatus.FRONTEND_SKIPPED)
                .sorted(Comparator
                        .comparing((SsaMethodResult result) -> result.sourceMethod().owner())
                        .thenComparing(result -> result.sourceMethod().name())
                        .thenComparing(result -> result.sourceMethod().descriptor()))
                .forEach(result -> {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("selector", result.sourceMethod().methodKey());
                    entry.addProperty("class", result.sourceMethod().owner());
                    entry.addProperty("method", result.sourceMethod().name());
                    entry.addProperty("descriptor", result.sourceMethod().descriptor());
                    entry.addProperty("status", result.status().wireName());
                    entry.addProperty("stage", "LOWERING");
                    entry.addProperty("reasonCode", result.reasonCode());
                    entry.addProperty("reason", result.reason());
                    entry.addProperty("affectsCallers", true);
                    entries.add(entry);
                });
        root.add("entries", entries);
        return GSON.toJson(root) + "\n";
    }
}
