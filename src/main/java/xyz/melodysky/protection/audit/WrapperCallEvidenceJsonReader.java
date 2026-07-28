package xyz.melodysky.protection.audit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Reads the hash-only wrapper evidence contract emitted by a Ghidra script,
 * another binary analyzer, or {@link WrapperCallShapeReportWriter}.
 */
public final class WrapperCallEvidenceJsonReader {
    public List<WrapperCallEvidence> read(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        return parse(Files.readString(path));
    }

    public List<WrapperCallEvidence> parse(String json) {
        Objects.requireNonNull(json, "json");
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray rows = requiredArray(root, "wrappers");
        ArrayList<WrapperCallEvidence> evidence = new ArrayList<>();
        for (JsonElement element : rows) {
            JsonObject row = element.getAsJsonObject();
            WrapperCallShape shape = shape(requiredString(row, "shape"));
            JsonElement fingerprint = row.get("resolutionFingerprintHash");
            String resolution = fingerprint == null || fingerprint.isJsonNull()
                    ? null
                    : fingerprint.getAsString();
            evidence.add(new WrapperCallEvidence(
                    requiredString(row, "bindingIdentityHash"),
                    shape,
                    resolution,
                    evidenceKind(requiredString(row, "evidenceKind"))));
        }
        return evidence.stream().sorted().toList();
    }

    private JsonArray requiredArray(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException(
                    "wrapper evidence JSON requires array: " + name);
        }
        return value.getAsJsonArray();
    }

    private String requiredString(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            throw new IllegalArgumentException(
                    "wrapper evidence JSON requires string: " + name);
        }
        String text = value.getAsString();
        if (text.isBlank()) {
            throw new IllegalArgumentException(
                    "wrapper evidence JSON string must not be blank: " + name);
        }
        return text;
    }

    private WrapperCallShape shape(String wireName) {
        for (WrapperCallShape value : WrapperCallShape.values()) {
            if (value.wireName().equals(wireName)) {
                return value;
            }
        }
        throw new IllegalArgumentException(
                "unknown wrapper call shape: " + wireName);
    }

    private WrapperEvidenceKind evidenceKind(String wireName) {
        for (WrapperEvidenceKind value : WrapperEvidenceKind.values()) {
            if (value.wireName().equals(wireName)) {
                return value;
            }
        }
        throw new IllegalArgumentException(
                "unknown wrapper evidence kind: " + wireName);
    }
}
