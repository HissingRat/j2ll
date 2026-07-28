package xyz.melodysky.protection.audit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

public final class DualBuildFingerprintReportWriter {
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    public String json(DualBuildFingerprintResult result) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("reportVersion", 1);
        root.addProperty("seedMode", result.seedMode().wireName());
        root.addProperty("nativeChanged", result.nativeChanged());
        root.addProperty("generatedCChanged", result.generatedCChanged());
        root.addProperty("combinedChanged", result.combinedChanged());
        JsonObject size = new JsonObject();
        size.addProperty(
                "firstNativeSizeBytes",
                result.firstNativeSizeBytes());
        size.addProperty(
                "secondNativeSizeBytes",
                result.secondNativeSizeBytes());
        size.addProperty(
                "nativeSizeDeltaBytes",
                result.nativeSizeDeltaBytes());
        size.addProperty(
                "firstGeneratedCSizeBytes",
                result.firstGeneratedCSizeBytes());
        size.addProperty(
                "secondGeneratedCSizeBytes",
                result.secondGeneratedCSizeBytes());
        size.addProperty(
                "generatedCSizeDeltaBytes",
                result.generatedCSizeDeltaBytes());
        root.add("artifactSizeEvidence", size);
        root.addProperty("passed", result.passed());
        root.addProperty("reasonCode", result.reasonCode());
        return GSON.toJson(root) + "\n";
    }
}
