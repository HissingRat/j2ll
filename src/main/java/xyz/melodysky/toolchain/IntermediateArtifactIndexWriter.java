package xyz.melodysky.toolchain;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import xyz.melodysky.config.IntermediatesConfig;

public final class IntermediateArtifactIndexWriter {
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls()
            .setPrettyPrinting()
            .create();

    public String classIndexJson(ClassArtifact artifact) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("internalName", artifact.internalName());
        root.addProperty("fullSha256", artifact.fullHash());
        root.addProperty("hashPrefixLength", artifact.hashPrefixLength());
        root.addProperty("directory", artifact.directory());
        root.addProperty("sourceEntry", artifact.sourceEntry());
        root.addProperty("safeInternalName", artifact.safeInternalName());
        return GSON.toJson(root) + "\n";
    }

    public String methodIndexJson(ClassArtifact owner, IntermediateArtifactLayout layout) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("class", owner.internalName());
        JsonArray methods = new JsonArray();
        for (MethodArtifact method : layout.methodsFor(owner.internalName())) {
            JsonObject entry = new JsonObject();
            entry.addProperty("class", method.owner());
            entry.addProperty("method", method.name());
            entry.addProperty("descriptor", method.descriptor());
            entry.addProperty("fullSha256", method.fullHash());
            entry.addProperty("hashPrefixLength", method.hashPrefixLength());
            entry.addProperty("methodId", method.methodId());
            entry.addProperty("safeMethodName", method.safeMethodName());
            entry.addProperty("status", method.status().wireName());
            methods.add(entry);
        }
        root.add("methods", methods);
        return GSON.toJson(root) + "\n";
    }

    public String manifestJson(
            Path workspaceRoot,
            IntermediatesConfig config,
            IntermediateArtifactLayout layout) throws IOException {
        Path intermediatesRoot = workspaceRoot.resolve("intermediates");
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("reportVersion", 1);
        root.addProperty("enabled", config.enabled());
        root.addProperty("includeDebugDumps", config.includeDebugDumps());
        root.addProperty("includePerClassIr", config.includePerClassIr());
        root.addProperty("includePerClassLlvm", config.includePerClassLlvm());
        root.addProperty("includePerClassC", config.includePerClassC());
        root.add("classes", classArtifacts(layout));
        root.add("files", files(workspaceRoot, intermediatesRoot));
        return GSON.toJson(root) + "\n";
    }

    private JsonArray classArtifacts(IntermediateArtifactLayout layout) {
        JsonArray classes = new JsonArray();
        layout.classes().stream()
                .sorted(java.util.Comparator.comparing(ClassArtifact::internalName))
                .forEach(artifact -> {
                    JsonObject object = new JsonObject();
                    object.addProperty("class", artifact.internalName());
                    object.addProperty("directory", artifact.directory());
                    object.addProperty("fullSha256", artifact.fullHash());
                    object.addProperty("sourceEntry", artifact.sourceEntry());
                    JsonArray methods = new JsonArray();
                    layout.methodsFor(artifact.internalName()).stream()
                            .sorted(java.util.Comparator
                                    .comparing(MethodArtifact::name)
                                    .thenComparing(MethodArtifact::descriptor))
                            .forEach(method -> {
                                JsonObject methodObject = new JsonObject();
                                methodObject.addProperty("method", method.name());
                                methodObject.addProperty("descriptor", method.descriptor());
                                methodObject.addProperty("methodId", method.methodId());
                                methodObject.addProperty("fullSha256", method.fullHash());
                                methodObject.addProperty("status", method.status().wireName());
                                methods.add(methodObject);
                            });
                    object.add("methods", methods);
                    classes.add(object);
                });
        return classes;
    }

    private JsonArray files(Path workspaceRoot, Path intermediatesRoot) throws IOException {
        JsonArray files = new JsonArray();
        if (!Files.isDirectory(intermediatesRoot)) {
            return files;
        }
        try (var stream = Files.walk(intermediatesRoot)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().equals("intermediates-manifest.json"))
                    .sorted()
                    .forEach(path -> files.add(file(workspaceRoot, path)));
        }
        return files;
    }

    private JsonObject file(Path workspaceRoot, Path path) {
        JsonObject object = new JsonObject();
        object.addProperty("path", workspaceRoot.relativize(path).toString().replace('\\', '/'));
        object.addProperty("sha256", sha256(path));
        object.addProperty("kind", kind(path));
        return object;
    }

    private String kind(Path path) {
        String normalized = path.toString().replace('\\', '/');
        if (normalized.endsWith(".ll")) {
            return "llvm";
        }
        if (normalized.endsWith(".c")) {
            return "c";
        }
        if (normalized.endsWith(".ir")) {
            return "ssa-ir";
        }
        if (normalized.endsWith(".cfg.txt") || normalized.endsWith(".cfg.json")) {
            return "debug-dump";
        }
        if (normalized.endsWith(".json")) {
            return "json";
        }
        return "other";
    }

    private String sha256(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
        } catch (IOException | NoSuchAlgorithmException error) {
            throw new IllegalStateException("failed to hash intermediate artifact " + path, error);
        }
    }
}
