package xyz.melodysky.protection.audit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public final class AttackerAuditReportWriter {
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    public String json(AttackerAuditMetrics metrics) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("reportVersion", 1);
        root.addProperty("target", metrics.target());
        root.addProperty("passed", metrics.passed());
        root.addProperty("nativeSha256", metrics.nativeSha256());
        root.addProperty("generatedCSha256", metrics.generatedCSha256());
        root.addProperty("nativeSizeBytes", metrics.nativeSizeBytes());
        root.addProperty("generatedCSizeBytes", metrics.generatedCSizeBytes());

        JsonObject recovery = new JsonObject();
        recovery.addProperty(
                "fallbackCarrierOccurrences",
                metrics.fallbackCarrierOccurrences());
        recovery.addProperty(
                "classMagicOccurrences",
                metrics.classMagicOccurrences());
        recovery.addProperty(
                "legacyGlobalMetadataOccurrences",
                metrics.legacyGlobalMetadataOccurrences());
        recovery.addProperty(
                "legacyDecodeAllOccurrences",
                metrics.legacyDecodeAllOccurrences());
        recovery.addProperty(
                "nativePrintableStringCount",
                metrics.nativePrintableStringCount());
        recovery.addProperty(
                "generatedCStringLiteralCount",
                metrics.generatedCStringLiteralCount());
        recovery.addProperty(
                "generatedNativeTextCipherArrayCount",
                metrics.generatedNativeTextCipherArrayCount());
        recovery.addProperty(
                "generatedNativeTextSiteCodecCount",
                metrics.generatedNativeTextSiteCodecCount());
        recovery.addProperty(
                "generatedNativeTextCodecFamilyCount",
                metrics.generatedNativeTextCodecFamilyCount());
        recovery.addProperty(
                "generatedNativeTextDecoderCount",
                metrics.generatedNativeTextDecoderCount());
        recovery.addProperty(
                "generatedNativeTextLargestDecoderFanout",
                metrics.generatedNativeTextLargestDecoderFanout());
        recovery.addProperty(
                "generatedNativeTextFixedShapeOccurrences",
                metrics.generatedNativeTextFixedShapeOccurrences());
        recovery.addProperty(
                "generatedNativeTextAdjacentSeedCipherOccurrences",
                metrics.generatedNativeTextAdjacentSeedCipherOccurrences());
        recovery.addProperty(
                "sensitivePlaintextOccurrences",
                metrics.sensitivePlaintextOccurrences());
        root.add("recoverySurface", recovery);

        JsonArray sensitive = new JsonArray();
        metrics.sensitivePlaintextMetrics().forEach(metric -> {
            JsonObject object = new JsonObject();
            object.addProperty("literalHash", metric.literalHash());
            object.addProperty(
                    "nativeUtf8Occurrences",
                    metric.nativeUtf8Occurrences());
            object.addProperty(
                    "nativeUtf16LeOccurrences",
                    metric.nativeUtf16LeOccurrences());
            object.addProperty(
                    "generatedCOccurrences",
                    metric.generatedCOccurrences());
            object.addProperty("totalOccurrences", metric.totalOccurrences());
            sensitive.add(object);
        });
        root.add("sensitivePlaintextMetrics", sensitive);
        root.add(
                "generatedCHardeningFindings",
                strings(metrics.generatedCHardeningFindings()));
        root.add(
                "generatedCHardeningEvidence",
                strings(metrics.generatedCHardeningEvidence()));
        root.add("dynamicExports", strings(metrics.dynamicExports()));
        root.add("unexpectedExports", strings(metrics.unexpectedExports()));
        root.add("missingExports", strings(metrics.missingExports()));
        return GSON.toJson(root) + "\n";
    }

    private JsonArray strings(java.util.List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }
}
