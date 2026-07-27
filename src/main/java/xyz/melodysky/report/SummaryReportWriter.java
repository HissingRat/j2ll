package xyz.melodysky.report;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SummaryReportWriter {
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls()
            .setPrettyPrinting()
            .create();

    public Path write(Path workspaceRoot, String mode, boolean finalArtifactWritten) throws IOException {
        Path reports = workspaceRoot.resolve("reports");
        Files.createDirectories(reports);
        Path summary = reports.resolve("summary.json");
        Files.writeString(summary, json(workspaceRoot, mode, finalArtifactWritten));
        return summary;
    }

    public String json(Path workspaceRoot, String mode, boolean finalArtifactWritten) throws IOException {
        Path reports = workspaceRoot.resolve("reports");
        JsonObject diagnostics = readJson(reports.resolve("diagnostics.json"));
        JsonObject lowering = readJson(reports.resolve("lowering-report.json"));
        JsonObject packaging = readJson(reports.resolve("packaging-report.json"));
        JsonObject protection = readJson(reports.resolve("protection-report.json"));
        JsonObject artifactAudit = readJson(reports.resolve("artifact-audit.json"));
        JsonObject readiness = readJson(reports.resolve("release-readiness.json"));
        JsonObject blockers = readJson(reports.resolve("known-blockers.json"));

        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("reportVersion", 1);
        root.addProperty("mode", mode);
        root.addProperty("status", status(diagnostics, artifactAudit, readiness, finalArtifactWritten));
        root.addProperty("finalArtifactWritten", finalArtifactWritten);
        root.add("outputJar", stringOrNull(packaging, "outputJar"));
        root.addProperty("reportsDir", "reports");
        root.add("diagnostics", diagnosticsSummary(diagnostics));
        root.add("methods", methodSummary(lowering));
        root.add("nativeTargets", nativeTargets(packaging));
        root.add("protection", protectionSummary(protection, artifactAudit));
        root.add("artifactAudit", artifactAuditSummary(artifactAudit));
        root.add("readiness", readinessSummary(readiness));
        root.add("topWarnings", topDiagnostics(diagnostics, "warning", 5));
        root.add("topBlockers", topBlockers(blockers, 5));
        return GSON.toJson(root) + "\n";
    }

    private String status(
            JsonObject diagnostics,
            JsonObject artifactAudit,
            JsonObject readiness,
            boolean finalArtifactWritten) {
        if (diagnosticCount(diagnostics, "error") > 0) {
            return "failed";
        }
        if (artifactAudit != null && artifactAudit.has("passed") && !artifactAudit.get("passed").getAsBoolean()) {
            return finalArtifactWritten ? "warning" : "incomplete";
        }
        if (readiness != null && text(readiness, "status").equals("failed")) {
            return "warning";
        }
        return finalArtifactWritten ? "passed" : "incomplete";
    }

    private JsonObject diagnosticsSummary(JsonObject diagnostics) {
        JsonObject object = new JsonObject();
        object.addProperty("errors", diagnosticCount(diagnostics, "error"));
        object.addProperty("warnings", diagnosticCount(diagnostics, "warning"));
        object.add("topErrors", topDiagnostics(diagnostics, "error", 5));
        return object;
    }

    private int diagnosticCount(JsonObject diagnostics, String severity) {
        int count = 0;
        for (JsonElement element : array(diagnostics, "diagnostics")) {
            JsonObject diagnostic = element.getAsJsonObject();
            if (severity.equals(text(diagnostic, "severity"))) {
                count++;
            }
        }
        return count;
    }

    private JsonArray topDiagnostics(JsonObject diagnostics, String severity, int limit) {
        JsonArray array = new JsonArray();
        int count = 0;
        for (JsonElement element : array(diagnostics, "diagnostics")) {
            JsonObject diagnostic = element.getAsJsonObject();
            if (!severity.equals(text(diagnostic, "severity"))) {
                continue;
            }
            JsonObject object = new JsonObject();
            object.addProperty("stage", text(diagnostic, "stage"));
            object.addProperty("reasonCode", text(diagnostic, "code"));
            object.addProperty("message", text(diagnostic, "message"));
            array.add(object);
            count++;
            if (count == limit) {
                break;
            }
        }
        return array;
    }

    private JsonObject methodSummary(JsonObject lowering) {
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        counts.put("nativeLowered", 0);
        counts.put("skipped", 0);
        counts.put("ineligible", 0);
        counts.put("excluded", 0);
        for (JsonElement element : array(lowering, "requestedMethods")) {
            String status = text(element.getAsJsonObject(), "status");
            counts.computeIfPresent(status, (ignored, value) -> value + 1);
        }
        counts.put("ineligible", array(lowering, "ineligible").size());
        counts.put("excluded", array(lowering, "excluded").size());
        JsonObject object = new JsonObject();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            object.addProperty(entry.getKey(), entry.getValue());
        }
        return object;
    }

    private JsonArray nativeTargets(JsonObject packaging) {
        JsonArray targets = new JsonArray();
        JsonObject zig = object(packaging, "zigToolchain");
        for (JsonElement element : array(zig, "targetArtifacts")) {
            JsonObject artifact = element.getAsJsonObject();
            JsonObject target = new JsonObject();
            target.addProperty("target", text(artifact, "target"));
            target.addProperty("required", bool(artifact, "required"));
            target.addProperty("currentHost", bool(artifact, "currentHost"));
            target.addProperty("status", text(artifact, "status"));
            target.addProperty("resourcePath", text(artifact, "expectedResourcePath"));
            target.add("sha256", stringOrNull(artifact, "actualSha256"));
            target.addProperty("failureKind", text(artifact, "failureKind"));
            targets.add(target);
        }
        return targets;
    }

    private JsonObject protectionSummary(JsonObject protection, JsonObject artifactAudit) {
        int ran = 0;
        int skipped = 0;
        for (JsonElement element : array(protection, "passes")) {
            JsonObject pass = element.getAsJsonObject();
            if ("RAN".equals(text(pass, "status"))) {
                ran++;
            } else {
                skipped++;
            }
        }
        JsonObject object = new JsonObject();
        object.addProperty("passesRan", ran);
        object.addProperty("passesSkipped", skipped);
        object.addProperty("blockingSensitiveFacts", array(artifactAudit, "checkedSensitiveFacts").size());
        object.addProperty("observedOnlySensitiveFacts", array(artifactAudit, "observedOnlySensitiveFacts").size());
        return object;
    }

    private JsonObject artifactAuditSummary(JsonObject artifactAudit) {
        JsonObject object = new JsonObject();
        if (artifactAudit == null) {
            object.addProperty("status", "missing");
            object.add("failedChecks", new JsonArray());
            return object;
        }
        object.addProperty("status", bool(artifactAudit, "passed") ? "passed" : "failed");
        JsonArray failed = new JsonArray();
        for (JsonElement element : array(artifactAudit, "checks")) {
            JsonObject check = element.getAsJsonObject();
            if (!"failed".equals(text(check, "status"))) {
                continue;
            }
            JsonObject objectCheck = new JsonObject();
            objectCheck.addProperty("name", text(check, "name"));
            objectCheck.addProperty("reasonCode", text(check, "reasonCode"));
            objectCheck.addProperty("message", text(check, "message"));
            failed.add(objectCheck);
        }
        object.add("failedChecks", failed);
        return object;
    }

    private JsonObject readinessSummary(JsonObject readiness) {
        JsonObject object = new JsonObject();
        if (readiness == null) {
            object.addProperty("status", "missing");
            object.addProperty("strictModePassed", false);
            object.addProperty("missingEvidenceCount", 0);
            object.add("topMissingEvidence", new JsonArray());
            return object;
        }
        object.addProperty("status", text(readiness, "status"));
        object.addProperty("strictModePassed", bool(readiness, "strictModePassed"));
        JsonArray missing = array(readiness, "missingEvidence");
        object.addProperty("missingEvidenceCount", missing.size());
        JsonArray top = new JsonArray();
        int count = 0;
        for (JsonElement element : missing) {
            JsonObject item = element.getAsJsonObject();
            JsonObject topItem = new JsonObject();
            topItem.addProperty("type", text(item, "type"));
            topItem.addProperty("reasonCode", text(item, "reasonCode"));
            topItem.addProperty("detail", text(item, "detail"));
            top.add(topItem);
            count++;
            if (count == 5) {
                break;
            }
        }
        object.add("topMissingEvidence", top);
        return object;
    }

    private JsonArray topBlockers(JsonObject blockers, int limit) {
        JsonArray array = new JsonArray();
        int count = 0;
        for (JsonElement element : array(blockers, "blockers")) {
            JsonObject blocker = element.getAsJsonObject();
            JsonObject object = new JsonObject();
            object.addProperty("id", text(blocker, "id"));
            object.addProperty("reasonCode", text(blocker, "reasonCode"));
            object.addProperty("reportLocation", text(blocker, "reportLocation"));
            array.add(object);
            count++;
            if (count == limit) {
                break;
            }
        }
        return array;
    }

    private JsonObject readJson(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }

    private JsonArray array(JsonObject object, String field) {
        if (object == null || !object.has(field) || !object.get(field).isJsonArray()) {
            return new JsonArray();
        }
        return object.getAsJsonArray(field);
    }

    private JsonObject object(JsonObject object, String field) {
        if (object == null || !object.has(field) || !object.get(field).isJsonObject()) {
            return null;
        }
        return object.getAsJsonObject(field);
    }

    private String text(JsonObject object, String field) {
        if (object == null || !object.has(field) || object.get(field).isJsonNull()) {
            return "";
        }
        return object.get(field).getAsString();
    }

    private boolean bool(JsonObject object, String field) {
        return object != null && object.has(field) && !object.get(field).isJsonNull() && object.get(field).getAsBoolean();
    }

    private JsonElement stringOrNull(JsonObject object, String field) {
        if (object == null || !object.has(field) || object.get(field).isJsonNull()) {
            return JsonNull.INSTANCE;
        }
        return object.get(field);
    }
}
