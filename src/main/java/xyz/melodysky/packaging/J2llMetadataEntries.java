package xyz.melodysky.packaging;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import xyz.melodysky.config.ResolvedConfig;
import xyz.melodysky.protection.BuildProtectionIdentity;
import xyz.melodysky.toolchain.NativeLibraryArtifact;
import xyz.melodysky.toolchain.ZigNativeBuildResult;

public final class J2llMetadataEntries {
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();
    private static final List<String> REPORT_NAMES = List.of(
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
            "index.json",
            "summary.json",
            "summary.md",
            "support-matrix.json",
            "symbol-audit.json");

    public Map<String, byte[]> entries(ResolvedConfig config, Optional<ZigNativeBuildResult> nativeBuildResult) {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/j2ll/build-info.json", bytes(buildInfo(config, nativeBuildResult)));
        entries.put("META-INF/j2ll/native-libraries.json", bytes(nativeLibraries(nativeBuildResult
                .map(ZigNativeBuildResult::artifacts)
                .orElse(List.of()))));
        entries.put("META-INF/j2ll/reports-manifest.json", bytes(reportsManifest()));
        return entries;
    }

    private String buildInfo(ResolvedConfig config, Optional<ZigNativeBuildResult> nativeBuildResult) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("reportVersion", 1);
        root.addProperty("tool", "j2ll");
        root.addProperty("toolVersion", "clean-room-rc-candidate");
        root.addProperty("configHash", sha256(normalizedConfigIdentity(config)));
        root.addProperty("protectionSeedMode", config.protection().seedMode().wireName());
        root.addProperty(
                "protectionSeedHash",
                BuildProtectionIdentity.from(config.protection()).identityHash());
        JsonArray targets = new JsonArray();
        nativeBuildResult
                .map(result -> result.artifacts().stream()
                        .map(artifact -> artifact.target().directoryName())
                        .toList())
                .orElse(config.targets().stream()
                        .map(target -> target.directoryName())
                        .toList())
                .stream()
                .sorted()
                .forEach(targets::add);
        root.add("selectedTargets", targets);
        nativeBuildResult
                .map(result -> result.zig().version())
                .ifPresentOrElse(
                        version -> root.addProperty("zigVersion", version),
                        () -> root.add("zigVersion", com.google.gson.JsonNull.INSTANCE));
        return GSON.toJson(root) + "\n";
    }

    private String nativeLibraries(List<NativeLibraryArtifact> artifacts) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("reportVersion", 1);
        JsonArray libraries = new JsonArray();
        artifacts.stream()
                .sorted(java.util.Comparator.comparing(artifact -> artifact.target().directoryName()))
                .forEach(artifact -> {
                    JsonObject object = new JsonObject();
                    object.addProperty("target", artifact.target().directoryName());
                    object.addProperty("jarPath", artifact.jarPath());
                    object.addProperty("sha256", artifact.sha256());
                    libraries.add(object);
                });
        root.add("libraries", libraries);
        return GSON.toJson(root) + "\n";
    }

    private String reportsManifest() {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("reportVersion", 1);
        JsonArray reports = new JsonArray();
        REPORT_NAMES.forEach(reports::add);
        root.add("reports", reports);
        root.addProperty("reportIndex", "reports/index.json");
        root.addProperty("reportHashSource", "workspaceReportIndexSha256");
        root.addProperty("reportsManifestHash", sha256(String.join("\n", REPORT_NAMES)));
        return GSON.toJson(root) + "\n";
    }

    private byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private String normalizedConfigIdentity(ResolvedConfig config) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", config.schemaVersion());
        root.addProperty("worldModel", config.worldModel().name());
        root.add("whiteList", sortedStrings(config.whiteList().stream()
                .map(selector -> selector.raw())
                .toList()));
        root.add("blackList", sortedStrings(config.blackList().stream()
                .map(selector -> selector.raw())
                .toList()));
        root.add("targets", sortedStrings(config.targets().stream()
                .map(target -> target.directoryName())
                .toList()));
        root.addProperty("embeddedLibraryDirectory", config.embeddedLibraryDirectory());
        root.addProperty("signaturePolicy", config.signaturePolicy().wireName());
        root.addProperty("protectionEnabled", config.protection().enabled());
        root.addProperty("protectionSeedMode", config.protection().seedMode().wireName());
        root.addProperty(
                "protectionSeedHash",
                BuildProtectionIdentity.from(config.protection()).identityHash());
        return GSON.toJson(root);
    }

    private JsonArray sortedStrings(List<String> values) {
        JsonArray array = new JsonArray();
        values.stream().sorted().forEach(array::add);
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
