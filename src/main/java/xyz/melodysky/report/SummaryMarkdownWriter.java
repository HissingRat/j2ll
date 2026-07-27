package xyz.melodysky.report;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class SummaryMarkdownWriter {
    public Path write(Path workspaceRoot) throws IOException {
        Path reports = workspaceRoot.resolve("reports");
        Files.createDirectories(reports);
        Path markdown = reports.resolve("summary.md");
        Files.writeString(markdown, markdown(workspaceRoot));
        return markdown;
    }

    public String markdown(Path workspaceRoot) throws IOException {
        Path summary = workspaceRoot.resolve("reports/summary.json");
        JsonObject root = Files.isRegularFile(summary)
                ? JsonParser.parseString(Files.readString(summary)).getAsJsonObject()
                : new JsonObject();
        StringBuilder builder = new StringBuilder();
        builder.append("# j2ll build summary\n\n");
        builder.append("- Status: ").append(text(root, "status", "missing")).append('\n');
        builder.append("- Final artifact written: ").append(boolText(root, "finalArtifactWritten")).append('\n');
        builder.append("- Output JAR: ").append(text(root, "outputJar", "null")).append('\n');
        builder.append("- Reports dir: ").append(text(root, "reportsDir", "reports")).append("\n\n");

        JsonObject diagnostics = object(root, "diagnostics");
        builder.append("## Diagnostics\n\n");
        builder.append("- Errors: ").append(numberText(diagnostics, "errors")).append('\n');
        builder.append("- Warnings: ").append(numberText(diagnostics, "warnings")).append("\n\n");

        JsonObject methods = object(root, "methods");
        builder.append("## Methods\n\n");
        builder.append("- Native lowered: ").append(numberText(methods, "nativeLowered")).append('\n');
        builder.append("- Skipped: ").append(numberText(methods, "skipped")).append('\n');
        builder.append("- Ineligible: ").append(numberText(methods, "ineligible")).append('\n');
        builder.append("- Excluded: ").append(numberText(methods, "excluded")).append("\n\n");

        JsonObject audit = object(root, "artifactAudit");
        JsonObject readiness = object(root, "readiness");
        builder.append("## Gates\n\n");
        builder.append("- Artifact audit: ").append(text(audit, "status", "missing")).append('\n');
        builder.append("- Readiness: ").append(text(readiness, "status", "missing")).append('\n');
        builder.append("- Missing readiness evidence: ")
                .append(numberText(readiness, "missingEvidenceCount"))
                .append('\n');
        appendNativeTargets(builder, root);
        appendBlockers(builder, workspaceRoot.resolve("reports/known-blockers.json"));
        return builder.toString();
    }

    private void appendNativeTargets(StringBuilder builder, JsonObject root) {
        builder.append("\n## Native Targets\n\n");
        if (root == null || !root.has("nativeTargets") || !root.get("nativeTargets").isJsonArray()) {
            builder.append("- Built/buildable: none\n");
            builder.append("- Unbuildable: none\n");
            return;
        }
        java.util.ArrayList<String> built = new java.util.ArrayList<>();
        java.util.ArrayList<String> unbuildable = new java.util.ArrayList<>();
        root.getAsJsonArray("nativeTargets").forEach(element -> {
            JsonObject target = element.getAsJsonObject();
            String line = text(target, "target", "unknown")
                    + " " + text(target, "status", "unknown")
                    + " " + text(target, "resourcePath", "unknown");
            String status = text(target, "status", "");
            if (status.equals("built") || status.equals("buildable") || status.equals("skipped")) {
                built.add(line);
            } else {
                unbuildable.add(line + " failureKind=" + text(target, "failureKind", "unknown"));
            }
        });
        built.sort(String::compareTo);
        unbuildable.sort(String::compareTo);
        builder.append("- Built/buildable: ").append(built.isEmpty() ? "none" : String.join(", ", built)).append('\n');
        builder.append("- Unbuildable: ").append(unbuildable.isEmpty() ? "none" : String.join(", ", unbuildable)).append('\n');
    }

    private void appendBlockers(StringBuilder builder, Path blockersPath) throws IOException {
        JsonObject blockers = Files.isRegularFile(blockersPath)
                ? JsonParser.parseString(Files.readString(blockersPath)).getAsJsonObject()
                : new JsonObject();
        builder.append("\n## Blockers\n\n");
        builder.append("- Beta blockers: ").append(blockerLine(blockers, "beta")).append('\n');
        builder.append("- 1.0 blockers: ").append(blockerLine(blockers, "1.0")).append('\n');
        builder.append("- Future/non-goals: ").append(blockerLine(blockers, "future")).append('\n');
    }

    private String blockerLine(JsonObject blockers, String group) {
        if (blockers == null || !blockers.has("blockers") || !blockers.get("blockers").isJsonArray()) {
            return "none";
        }
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        blockers.getAsJsonArray("blockers").forEach(element -> {
            JsonObject blocker = element.getAsJsonObject();
            if (blockerGroup(blocker).equals(group)) {
                values.add(text(blocker, "id", "unknown") + " (" + text(blocker, "reasonCode", "UNKNOWN") + ")");
            }
        });
        values.sort(String::compareTo);
        return values.isEmpty() ? "none" : String.join(", ", values);
    }

    private String blockerGroup(JsonObject blocker) {
        String severity = text(blocker, "severity", "");
        String milestone = text(blocker, "targetMilestone", "");
        if (severity.equals("beta-blocker") || milestone.equals("beta")) {
            return "beta";
        }
        if (severity.equals("rc-blocker") || milestone.equals("rc") || milestone.equals("1.0")) {
            return "1.0";
        }
        return "future";
    }

    private JsonObject object(JsonObject root, String field) {
        if (root == null || !root.has(field) || !root.get(field).isJsonObject()) {
            return new JsonObject();
        }
        return root.getAsJsonObject(field);
    }

    private String text(JsonObject object, String field, String defaultValue) {
        if (object == null || !object.has(field) || object.get(field).isJsonNull()) {
            return defaultValue;
        }
        JsonElement value = object.get(field);
        return value.isJsonPrimitive() ? value.getAsString() : defaultValue;
    }

    private String boolText(JsonObject object, String field) {
        if (object == null || !object.has(field) || object.get(field).isJsonNull()) {
            return "false";
        }
        return Boolean.toString(object.get(field).getAsBoolean());
    }

    private String numberText(JsonObject object, String field) {
        if (object == null || !object.has(field) || object.get(field).isJsonNull()) {
            return "0";
        }
        return object.get(field).getAsString();
    }
}
