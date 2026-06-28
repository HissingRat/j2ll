package xyz.melodysky.report;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import xyz.melodysky.config.ResolvedConfig;
import xyz.melodysky.config.Selector;
import xyz.melodysky.toolchain.TargetTriple;

public final class ResolvedConfigReportWriter {
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls()
            .setPrettyPrinting()
            .create();

    public String json(ResolvedConfig config) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", config.schemaVersion());
        root.addProperty("jarFile", config.jarFile().toString());
        JsonArray classPath = new JsonArray();
        config.classPath().forEach(path -> classPath.add(path.toString()));
        root.add("classPath", classPath);
        root.addProperty("javaHome", config.javaHome() == null ? null : config.javaHome().toString());
        root.addProperty("runtimeImage", config.runtimeImage() == null ? null : config.runtimeImage().toString());
        root.addProperty("worldModel", config.worldModel().name());
        root.addProperty("javaSupportTier", config.javaSupportTier().name());
        root.addProperty("fallbackMode", config.fallbackMode().wireName());
        root.addProperty("outputDirectory", config.outputDirectory().toString());
        root.add("whiteList", selectors(config.whiteList()));
        root.add("blackList", selectors(config.blackList()));
        JsonArray targets = new JsonArray();
        for (TargetTriple target : config.targets()) {
            targets.add(target.directoryName());
        }
        root.add("targets", targets);
        root.addProperty("libraryName", config.libraryName());
        root.addProperty("embeddedLibraryDirectory", config.embeddedLibraryDirectory());
        root.addProperty("signaturePolicy", config.signaturePolicy().wireName());
        root.addProperty("protectionSeedHash", sha256(config.protection().seed()));
        return GSON.toJson(root) + "\n";
    }

    private JsonArray selectors(java.util.List<Selector> selectors) {
        JsonArray array = new JsonArray();
        selectors.forEach(selector -> array.add(selector.raw()));
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
