package xyz.melodysky.protection.audit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;

/** Stable hash-only JSON writer for wrapper shape and reuse metrics. */
public final class WrapperCallShapeReportWriter {
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    public String json(WrapperCallShapeMetric metric) {
        JsonObject root = header();
        root.addProperty("wrapperCount", metric.wrapperCount());
        root.addProperty(
                "resolvedWrapperCount",
                metric.resolvedWrapperCount());
        root.addProperty(
                "registeredEntryNoResolvedEdgeCount",
                metric.registeredEntryNoResolvedEdgeCount());
        root.addProperty(
                "directSingleCalleeCount",
                metric.directSingleCalleeCount());
        root.addProperty("indirectWrapperCount", metric.indirectWrapperCount());
        root.addProperty(
                "multipleCalleeCount",
                metric.multipleCalleeCount());
        root.addProperty(
                "unresolvedWrapperCount",
                metric.unresolvedWrapperCount());
        root.addProperty("finalBinaryEvidence", metric.finalBinaryEvidence());
        root.add("evidenceKindCounts", counts(metric.evidenceKindCounts()));
        JsonArray wrappers = new JsonArray();
        metric.wrappers().forEach(wrapper -> {
            JsonObject row = new JsonObject();
            row.addProperty(
                    "bindingIdentityHash",
                    wrapper.bindingIdentityHash());
            row.addProperty("shape", wrapper.shape().wireName());
            if (wrapper.resolutionFingerprintHash() == null) {
                row.add("resolutionFingerprintHash", null);
            } else {
                row.addProperty(
                        "resolutionFingerprintHash",
                        wrapper.resolutionFingerprintHash());
            }
            row.addProperty(
                    "evidenceKind",
                    wrapper.evidenceKind().wireName());
            row.addProperty(
                    "finalBinaryEvidence",
                    wrapper.evidenceKind().finalBinaryEvidence());
            wrappers.add(row);
        });
        root.add("wrappers", wrappers);
        return GSON.toJson(root) + "\n";
    }

    public String diffJson(WrapperMappingReuseMetric metric) {
        JsonObject root = header();
        root.addProperty("firstWrapperCount", metric.firstWrapperCount());
        root.addProperty("secondWrapperCount", metric.secondWrapperCount());
        root.addProperty("commonBindingCount", metric.commonBindingCount());
        root.addProperty(
                "reusableMappingCount",
                metric.reusableMappingCount());
        root.addProperty(
                "reuseRateBasisPoints",
                metric.reuseRateBasisPoints());
        root.addProperty("shapeChangedCount", metric.shapeChangedCount());
        root.addProperty(
                "resolutionChangedCount",
                metric.resolutionChangedCount());
        root.addProperty(
                "unresolvedCommonCount",
                metric.unresolvedCommonCount());
        root.addProperty("finalBinaryEvidence", metric.finalBinaryEvidence());
        root.add(
                "reusableBindingHashes",
                strings(metric.reusableBindingHashes()));
        root.add(
                "shapeChangedBindingHashes",
                strings(metric.shapeChangedBindingHashes()));
        root.add(
                "resolutionChangedBindingHashes",
                strings(metric.resolutionChangedBindingHashes()));
        root.add(
                "unresolvedBindingHashes",
                strings(metric.unresolvedBindingHashes()));
        root.add("addedBindingHashes", strings(metric.addedBindingHashes()));
        root.add(
                "removedBindingHashes",
                strings(metric.removedBindingHashes()));
        root.addProperty("reasonCode", metric.reasonCode());
        return GSON.toJson(root) + "\n";
    }

    private JsonObject header() {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("reportVersion", 1);
        return root;
    }

    private JsonObject counts(Map<String, Integer> values) {
        JsonObject object = new JsonObject();
        values.forEach(object::addProperty);
        return object;
    }

    private JsonArray strings(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }
}
