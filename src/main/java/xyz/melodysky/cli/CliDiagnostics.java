package xyz.melodysky.cli;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticHints;
import xyz.melodysky.diagnostic.DiagnosticStage;

final class CliDiagnostics {
    int exitCodeFor(List<Diagnostic> diagnostics) {
        return diagnostics.stream()
                .filter(diagnostic -> diagnostic.severity().wireName().equals("error"))
                .sorted(Comparator.comparingInt(this::exitCode).thenComparing(Diagnostic::code))
                .mapToInt(this::exitCode)
                .findFirst()
                .orElse(1);
    }

    String primaryFailure(List<Diagnostic> diagnostics) {
        return diagnostics.stream()
                .filter(diagnostic -> diagnostic.severity().wireName().equals("error"))
                .sorted()
                .findFirst()
                .map(diagnostic -> diagnostic.stage() + " " + diagnostic.code().value() + ": " + diagnostic.message())
                .orElse("unexpected internal error");
    }

    Optional<String> primaryHint(List<Diagnostic> diagnostics) {
        return diagnostics.stream()
                .filter(diagnostic -> diagnostic.severity().wireName().equals("error"))
                .sorted()
                .map(DiagnosticHints::hint)
                .filter(hint -> !hint.isBlank())
                .findFirst();
    }

    List<String> readinessFailureDetails(Path workspace) throws IOException {
        Path report = workspace.resolve("reports/release-readiness.json");
        if (!Files.isRegularFile(report)) {
            return List.of("releaseReadinessReport=" + report + " (missing)");
        }
        JsonObject root = JsonParser.parseString(Files.readString(report)).getAsJsonObject();
        ArrayList<String> lines = new ArrayList<>();
        lines.add("releaseReadinessReport=" + report);
        if (root.has("missingEvidence") && root.get("missingEvidence").isJsonArray()) {
            int count = 0;
            for (JsonElement element : root.getAsJsonArray("missingEvidence")) {
                JsonObject item = element.getAsJsonObject();
                lines.add("missingEvidence="
                        + text(item, "type") + " "
                        + text(item, "reasonCode") + " "
                        + text(item, "detail"));
                if (++count == 3) {
                    break;
                }
            }
        }
        return List.copyOf(lines);
    }

    private int exitCode(Diagnostic diagnostic) {
        if (diagnostic.stage() == DiagnosticStage.CONFIG) {
            return 2;
        }
        if (diagnostic.stage() == DiagnosticStage.PARSE
                || diagnostic.stage() == DiagnosticStage.CFG
                || diagnostic.stage() == DiagnosticStage.LOWERING
                || diagnostic.stage() == DiagnosticStage.VALIDATION
                || diagnostic.stage() == DiagnosticStage.LLVM_MODEL
                || diagnostic.stage() == DiagnosticStage.LLVM_EMISSION) {
            return 3;
        }
        if (diagnostic.stage() == DiagnosticStage.NATIVE_LINK
                || diagnostic.stage() == DiagnosticStage.SYMBOL_AUDIT) {
            return 4;
        }
        if (diagnostic.stage() == DiagnosticStage.PACKAGING) {
            return 5;
        }
        if (diagnostic.stage() == DiagnosticStage.ARTIFACT_AUDIT) {
            return 6;
        }
        if (diagnostic.stage() == DiagnosticStage.RELEASE_READINESS) {
            return 7;
        }
        return 1;
    }

    private String text(JsonObject object, String field) {
        return object.has(field) && !object.get(field).isJsonNull()
                ? object.get(field).getAsString()
                : "";
    }
}
