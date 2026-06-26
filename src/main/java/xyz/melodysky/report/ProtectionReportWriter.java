package xyz.melodysky.report;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Comparator;
import java.util.List;

public final class ProtectionReportWriter {
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls()
            .setPrettyPrinting()
            .create();

    public String json(String seed, List<ProtectionPassReport> reports) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("seed", seed);
        JsonArray passes = new JsonArray();
        reports.stream()
                .sorted(Comparator
                        .comparing(ProtectionPassReport::layer)
                        .thenComparing(ProtectionPassReport::passName)
                        .thenComparing(ProtectionPassReport::status)
                        .thenComparing(ProtectionPassReport::reasonCode))
                .forEach(report -> passes.add(passJson(report)));
        root.add("passes", passes);
        return GSON.toJson(root) + "\n";
    }

    private JsonObject passJson(ProtectionPassReport report) {
        JsonObject object = new JsonObject();
        object.addProperty("passName", report.passName());
        object.addProperty("layer", report.layer());
        object.addProperty("status", report.status());
        object.addProperty("reasonCode", report.reasonCode());
        object.add("affectedMethods", stringArray(report.affectedMethods()));
        object.add("affectedSymbols", stringArray(report.affectedSymbols()));
        object.addProperty("seed", report.seed());
        return object;
    }

    private JsonArray stringArray(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }
}
