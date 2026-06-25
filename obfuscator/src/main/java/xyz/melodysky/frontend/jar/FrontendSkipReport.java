package xyz.melodysky.frontend.jar;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import xyz.melodysky.frontend.bytecode.ClassIrBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class FrontendSkipReport {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final List<Entry> entries;
    private final Map<String, Integer> countsByCategory;

    private FrontendSkipReport(List<Entry> entries, Map<String, Integer> countsByCategory) {
        this.entries = List.copyOf(entries);
        this.countsByCategory = Collections.unmodifiableMap(new LinkedHashMap<>(countsByCategory));
    }

    public static FrontendSkipReport from(JarIrBuilder.BuildResult frontendResult) {
        ArrayList<Entry> entries = new ArrayList<>();
        LinkedHashMap<String, Integer> countsByCategory = new LinkedHashMap<>();
        for (JarIrBuilder.ClassBuildResult classResult : frontendResult.classResults()) {
            for (ClassIrBuilder.SkippedMethod skippedMethod : classResult.skippedMethods()) {
                String category = category(skippedMethod.reason());
                countsByCategory.merge(category, 1, Integer::sum);
                entries.add(new Entry(
                        classResult.className(),
                        skippedMethod.name(),
                        skippedMethod.descriptor(),
                        skippedMethod.reason(),
                        category
                ));
            }
        }
        return new FrontendSkipReport(entries, countsByCategory);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public String toText() {
        StringBuilder builder = new StringBuilder();
        String currentClassName = null;
        for (Entry entry : entries) {
            if (!entry.className().equals(currentClassName)) {
                currentClassName = entry.className();
                builder.append(currentClassName).append('\n');
            }
            builder.append("  - ")
                    .append(entry.methodName())
                    .append(entry.descriptor())
                    .append(" :: ")
                    .append(entry.reason())
                    .append('\n');
        }
        return builder.toString();
    }

    public String toJson() {
        return GSON.toJson(new JsonReport(entries.size(), countsByCategory, entries));
    }

    private static String category(String reason) {
        if (reason == null || reason.isBlank()) {
            return "unknown";
        }
        String normalized = reason.toLowerCase(Locale.ROOT);
        if (normalized.contains("annotation class")) {
            return "annotation-class";
        }
        if (normalized.contains("skipped method")) {
            return "dependency-skip";
        }
        if (normalized.contains("invokedynamic")) {
            return "invokedynamic";
        }
        if (normalized.contains("opcode")) {
            return "opcode";
        }
        if (normalized.contains("local slot")) {
            return "local-type";
        }
        return "unsupported-bytecode";
    }

    public record Entry(String className, String methodName, String descriptor, String reason, String category) {
    }

    private record JsonReport(int totalSkips, Map<String, Integer> countsByCategory, List<Entry> entries) {
    }
}
