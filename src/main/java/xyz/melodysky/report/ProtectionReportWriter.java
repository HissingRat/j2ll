package xyz.melodysky.report;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import xyz.melodysky.config.ProtectionSeedMode;
import xyz.melodysky.protection.audit.ProtectionCoverageReportWriter;
import xyz.melodysky.protection.audit.ProtectionPassCoverageAggregator;

public final class ProtectionReportWriter {
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls()
            .setPrettyPrinting()
            .create();

    public String json(String seed, List<ProtectionPassReport> reports) {
        return json(null, sha256(seed), reports);
    }

    public String json(
            ProtectionSeedMode seedMode,
            String buildIdentityHash,
            List<ProtectionPassReport> reports) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("reportVersion", 1);
        if (seedMode != null) {
            root.addProperty("seedMode", seedMode.wireName());
        }
        root.addProperty("seedHash", buildIdentityHash);
        root.add("sensitivePlaintextFacts", sensitivePlaintextFacts(reports));
        root.add("coverage", coverage(reports));
        JsonArray passes = new JsonArray();
        reports.stream()
                .sorted(Comparator
                        .comparing(ProtectionPassReport::layer)
                        .thenComparing(ProtectionPassReport::passName)
                        .thenComparing(ProtectionPassReport::status)
                        .thenComparing(ProtectionPassReport::reasonCode))
                .forEach(report -> passes.add(passJson(report)));
        root.add("passes", passes);
        return GSON.toJson(root) + "\n";
    }

    private JsonObject coverage(List<ProtectionPassReport> reports) {
        var facts = new ProtectionReportCoverageCollector().collect(reports);
        var snapshot = new ProtectionPassCoverageAggregator().aggregate(facts);
        JsonObject coverage = JsonParser.parseString(
                        new ProtectionCoverageReportWriter().json(snapshot))
                .getAsJsonObject();
        coverage.remove("schemaVersion");
        coverage.remove("reportVersion");
        return coverage;
    }

    private JsonObject passJson(ProtectionPassReport report) {
        JsonObject object = new JsonObject();
        object.addProperty("passName", report.passName());
        object.addProperty("layer", report.layer());
        object.addProperty("status", report.status());
        object.addProperty("reasonCode", report.reasonCode());
        object.add("affectedMethods", stringArray(report.affectedMethods()));
        object.add("affectedSymbols", stringArray(report.affectedSymbols()));
        object.add("sensitivePlaintextFacts", sensitivePlaintextFacts(List.of(report)));
        object.addProperty("seedHash", sha256(report.seed()));
        return object;
    }

    private JsonArray sensitivePlaintextFacts(List<ProtectionPassReport> reports) {
        JsonArray array = new JsonArray();
        reports.stream()
                .flatMap(report -> report.sensitivePlaintextFacts().stream())
                .sorted(Comparator
                        .comparing(SensitivePlaintextFact::literalHash)
                        .thenComparing(SensitivePlaintextFact::sourceMethod)
                        .thenComparing(SensitivePlaintextFact::passName)
                        .thenComparing(SensitivePlaintextFact::pathKind)
                        .thenComparing(SensitivePlaintextFact::gateMode)
                        .thenComparing(SensitivePlaintextFact::promotionReason))
                .forEach(fact -> {
                    JsonObject object = new JsonObject();
                    object.addProperty("literalHash", fact.literalHash());
                    object.addProperty("sourceMethod", fact.sourceMethod());
                    object.addProperty("passName", fact.passName());
                    object.addProperty("pathKind", fact.pathKind());
                    object.addProperty("gateMode", fact.gateMode());
                    object.addProperty("sourceSurface", fact.sourceSurface());
                    object.addProperty("reason", fact.reason());
                    object.addProperty("promotionReason", fact.promotionReason());
                    object.add("artifactSurfaces", stringArray(fact.artifactSurfaces()));
                    array.add(object);
                });
        return array;
    }

    private JsonArray stringArray(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
