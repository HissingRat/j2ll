package xyz.melodysky.report;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.List;
import xyz.melodysky.toolchain.TargetTriple;
import xyz.melodysky.toolchain.symbols.SymbolAuditResult;

public final class SymbolAuditReportWriter {
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls()
            .setPrettyPrinting()
            .create();

    public String json(List<LibraryAuditReport> libraries) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        JsonArray array = new JsonArray();
        for (LibraryAuditReport library : libraries) {
            JsonObject object = new JsonObject();
            object.addProperty("target", library.target().directoryName());
            object.addProperty("path", library.path().toString().replace('\\', '/'));
            object.add("allowedExports", stringArray(library.result().allowedExports()));
            object.add("actualExports", stringArray(library.result().actualExports()));
            object.add("unexpectedExports", stringArray(library.result().unexpectedExports()));
            object.add("missingExports", stringArray(library.result().missingExports()));
            object.addProperty("status", library.result().passed() ? "passed" : "failed");
            array.add(object);
        }
        root.add("libraries", array);
        return GSON.toJson(root) + "\n";
    }

    private JsonArray stringArray(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    public record LibraryAuditReport(TargetTriple target, Path path, SymbolAuditResult result) {
    }
}
