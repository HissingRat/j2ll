package xyz.melodysky.protection.audit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.List;

/** Stable hash-only JSON writer for dynamic registration capture evidence. */
public final class RegistrationCaptureReportWriter {
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    public String json(RegistrationCaptureMetric metric) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("reportVersion", 1);
        root.addProperty("observationChannel", metric.observationChannel());
        root.addProperty("jniOnLoadResult", metric.jniOnLoadResult());
        root.addProperty(
                "jniOnLoadExportPresent",
                metric.jniOnLoadExportPresent());
        root.addProperty(
                "stableDirectRegistrationExportPresent",
                metric.stableDirectRegistrationExportPresent());
        root.addProperty(
                "mappingAvailableOnlyAfterJniOnLoadObservation",
                metric.mappingAvailableOnlyAfterJniOnLoadObservation());
        root.addProperty("capturedOwnerCount", metric.capturedOwnerCount());
        root.addProperty("capturedBindingCount", metric.capturedBindingCount());
        root.add("bindings", bindings(metric));
        root.add("dynamicExports", strings(metric.dynamicExports()));
        root.addProperty("passed", metric.passed());
        root.addProperty("reasonCode", metric.reasonCode());
        return GSON.toJson(root) + "\n";
    }

    private JsonArray bindings(RegistrationCaptureMetric metric) {
        JsonArray array = new JsonArray();
        metric.bindings().forEach(binding -> {
            JsonObject object = new JsonObject();
            object.addProperty("ownerHash", binding.ownerHash());
            object.addProperty("methodNameHash", binding.methodNameHash());
            object.addProperty("descriptorHash", binding.descriptorHash());
            object.addProperty(
                    "functionIdentityHash",
                    binding.functionIdentityHash());
            array.add(object);
        });
        return array;
    }

    private JsonArray strings(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }
}
