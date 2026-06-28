package xyz.melodysky.report;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.List;
import xyz.melodysky.toolchain.NativeBuildPlan;
import xyz.melodysky.toolchain.NativeBuildTargetPreflight;

public final class DryRunReportWriter {
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    public String json(
            Path configPath,
            Path workspace,
            boolean inputJarParsed,
            int parsedClassCount,
            int requestedMethodCount,
            int notApplicableMethodCount,
            int excludedMethodCount,
            NativeBuildPlan nativeBuildPlan,
            List<String> diagnostics) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("reportVersion", 1);
        root.addProperty("mode", "dry-run");
        root.addProperty("configPath", configPath.toString().replace('\\', '/'));
        root.addProperty("workspace", workspace.toString().replace('\\', '/'));
        root.addProperty("configValidated", true);
        root.addProperty("inputJarParsed", inputJarParsed);
        root.addProperty("parsedClassCount", parsedClassCount);
        root.addProperty("requestedMethodCount", requestedMethodCount);
        root.addProperty("notApplicableMethodCount", notApplicableMethodCount);
        root.addProperty("excludedMethodCount", excludedMethodCount);
        root.addProperty("nativeBuildInvoked", false);
        root.addProperty("finalArtifactWritten", false);
        root.add("targetPlan", targetPlan(nativeBuildPlan));
        JsonArray diagnosticArray = new JsonArray();
        diagnostics.stream().sorted().forEach(diagnosticArray::add);
        root.add("diagnostics", diagnosticArray);
        return GSON.toJson(root) + "\n";
    }

    private JsonArray targetPlan(NativeBuildPlan plan) {
        JsonArray array = new JsonArray();
        for (NativeBuildTargetPreflight preflight : plan.targetPreflights()) {
            JsonObject object = new JsonObject();
            object.addProperty("target", preflight.target().directoryName());
            object.addProperty("required", preflight.required());
            object.addProperty("currentHost", preflight.currentHost());
            object.addProperty("buildable", preflight.buildable());
            object.addProperty("status", preflight.status());
            object.addProperty("reasonCode", preflight.reasonCode());
            object.addProperty("reason", preflight.reason());
            object.addProperty("expectedArtifactPath", preflight.outputPath().toString().replace('\\', '/'));
            object.addProperty("libraryName", preflight.libraryName());
            object.addProperty("failureKind", preflight.failureKind());
            array.add(object);
        }
        return array;
    }
}
