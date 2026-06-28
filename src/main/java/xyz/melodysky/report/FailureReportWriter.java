package xyz.melodysky.report;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticHints;

public final class FailureReportWriter {
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls()
            .setPrettyPrinting()
            .create();

    public String json(List<Diagnostic> diagnostics, boolean finalArtifactWritten) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("reportVersion", 1);
        root.addProperty("finalArtifactWritten", finalArtifactWritten);
        root.addProperty("primaryDiagnosticId", primaryDiagnosticId(diagnostics));
        JsonArray failures = new JsonArray();
        diagnostics.stream()
                .filter(diagnostic -> diagnostic.severity().wireName().equals("error"))
                .sorted()
                .forEach(diagnostic -> {
                    JsonObject object = new JsonObject();
                    object.addProperty("stage", diagnostic.stage().name());
                    object.addProperty("reasonCode", diagnostic.code().value());
                    object.addProperty("message", diagnostic.message());
                    object.addProperty("hint", DiagnosticHints.hint(diagnostic));
                    object.addProperty("decision", diagnostic.decision());
                    object.addProperty("affectedClass", diagnostic.location().className());
                    object.addProperty("affectedMethod", diagnostic.location().methodName());
                    object.addProperty("affectedDescriptor", diagnostic.location().descriptor());
                    object.addProperty("affectedArtifact", diagnostic.location().artifactId());
                    failures.add(object);
                });
        root.add("failures", failures);
        return GSON.toJson(root) + "\n";
    }

    private String primaryDiagnosticId(List<Diagnostic> diagnostics) {
        return diagnostics.stream()
                .filter(diagnostic -> diagnostic.severity().wireName().equals("error"))
                .sorted()
                .findFirst()
                .map(diagnostic -> diagnostic.stage().name() + ":" + diagnostic.code().value())
                .orElse("NONE");
    }
}
