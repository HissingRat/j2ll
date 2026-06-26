package xyz.melodysky.toolchain;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

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
}
