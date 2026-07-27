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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Set;
import java.util.stream.Stream;

public final class ReportIndexWriter {
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls()
            .setPrettyPrinting()
            .create();
    private static final Set<String> REQUIRED_FOR_BETA = Set.of(
            "diagnostics.json",
            "artifact-audit.json",
            "field-internalization-report.json",
            "known-blockers.json",
            "lowering-report.json",
            "opcode-support-matrix.json",
            "packaging-report.json",
            "protection-report.json",
            "release-readiness.json",
            "skipped-method-report.json",
            "summary.json",
            "summary.md",
            "support-matrix.json",
            "symbol-audit.json");
    private static final Set<String> REQUIRED_FOR_RC = Set.of(
            "diagnostics.json",
            "artifact-audit.json",
            "field-internalization-report.json",
            "known-blockers.json",
            "lowering-report.json",
            "opcode-support-matrix.json",
            "packaging-report.json",
            "protection-report.json",
            "release-readiness.json",
            "skipped-method-report.json",
            "support-matrix.json",
            "summary.json",
            "summary.md",
            "symbol-audit.json");
    private static final Set<String> PRODUCED_ON_FAILURE = Set.of(
            "diagnostics.json",
            "failure-report.json",
            "artifact-audit.json",
            "field-internalization-report.json",
            "skipped-method-report.json",
            "lowering-report.json",
            "packaging-report.json",
            "release-readiness.json",
            "summary.json",
            "summary.md");

    public Path write(Path workspaceRoot) throws IOException {
        Path reports = workspaceRoot.resolve("reports");
        Files.createDirectories(reports);
        Path index = reports.resolve("index.json");
        Files.writeString(index, json(workspaceRoot));
        return index;
    }

    public String json(Path workspaceRoot) throws IOException {
        Path reports = workspaceRoot.resolve("reports");
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("reportVersion", 1);
        JsonArray array = new JsonArray();
        discoverReports(workspaceRoot).forEach(path -> array.add(reportEntry(workspaceRoot, path)));
        root.add("reports", array);
        return GSON.toJson(root) + "\n";
    }

    private java.util.List<Path> discoverReports(Path workspaceRoot) throws IOException {
        java.util.ArrayList<Path> paths = new java.util.ArrayList<>();
        Path reports = workspaceRoot.resolve("reports");
        if (Files.isDirectory(reports)) {
            try (Stream<Path> stream = Files.list(reports)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> !path.getFileName().toString().equals("index.json"))
                        .filter(path -> isReport(path.getFileName().toString()))
                        .forEach(paths::add);
            }
        }
        Path rootConfig = workspaceRoot.resolve("config.resolved.json");
        if (Files.isRegularFile(rootConfig)) {
            paths.add(rootConfig);
        }
        Path intermediates = workspaceRoot.resolve("intermediates/intermediates-manifest.json");
        if (Files.isRegularFile(intermediates)) {
            paths.add(intermediates);
        }
        return paths.stream()
                .sorted(Comparator.comparing(path -> workspaceRoot.relativize(path).toString().replace('\\', '/')))
                .toList();
    }

    private JsonObject reportEntry(Path workspaceRoot, Path path) {
        String name = path.getFileName().toString();
        String relativePath = workspaceRoot.relativize(path).toString().replace('\\', '/');
        JsonObject object = new JsonObject();
        object.addProperty("path", relativePath);
        object.add("reportVersion", reportVersion(path));
        object.addProperty("sha256", sha256(path));
        object.addProperty("requiredForReadiness", REQUIRED_FOR_RC.contains(name));
        object.addProperty("requiredForBeta", REQUIRED_FOR_BETA.contains(name) || relativePath.equals("config.resolved.json"));
        object.addProperty("requiredForRc", REQUIRED_FOR_RC.contains(name) || relativePath.equals("config.resolved.json"));
        object.addProperty("producedOnFailure", PRODUCED_ON_FAILURE.contains(name));
        object.addProperty("status", status(name, path));
        return object;
    }

    private boolean isReport(String name) {
        return name.endsWith(".json") || name.endsWith(".md");
    }

    private JsonElement reportVersion(Path path) {
        if (!path.getFileName().toString().endsWith(".json")) {
            return JsonNull.INSTANCE;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            return root.has("reportVersion") && !root.get("reportVersion").isJsonNull()
                    ? root.get("reportVersion")
                    : JsonNull.INSTANCE;
        } catch (RuntimeException | IOException exception) {
            return JsonNull.INSTANCE;
        }
    }

    private String status(String name, Path path) {
        if (name.equals("failure-report.json")) {
            return "failed";
        }
        if (name.equals("summary.md")) {
            return "present";
        }
        if (!name.endsWith(".json")) {
            return "present";
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            if (root.has("status") && !root.get("status").isJsonNull()) {
                return root.get("status").getAsString();
            }
            if (name.equals("artifact-audit.json") && root.has("passed")) {
                return root.get("passed").getAsBoolean() ? "passed" : "failed";
            }
            if (name.equals("diagnostics.json") && root.has("diagnostics")) {
                boolean hasError = false;
                boolean hasWarning = false;
                for (JsonElement element : root.getAsJsonArray("diagnostics")) {
                    JsonObject diagnostic = element.getAsJsonObject();
                    String severity = diagnostic.has("severity") ? diagnostic.get("severity").getAsString() : "";
                    hasError |= severity.equals("error");
                    hasWarning |= severity.equals("warning");
                }
                if (hasError) {
                    return "failed";
                }
                return hasWarning ? "warning" : "passed";
            }
            return "present";
        } catch (RuntimeException | IOException exception) {
            return "unreadable";
        }
    }

    private String sha256(Path path) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to hash report " + path, exception);
        }
    }
}
