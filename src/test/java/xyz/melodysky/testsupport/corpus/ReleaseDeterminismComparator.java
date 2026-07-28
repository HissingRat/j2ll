package xyz.melodysky.testsupport.corpus;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

public final class ReleaseDeterminismComparator {
    private static final Pattern FALLBACK_ID = Pattern.compile("\"fallbackId\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern STRING_TOKEN =
            Pattern.compile("enc:v(?:1|2):(-?[0-9]+):");
    private static final Pattern HIDDEN_SYMBOL = Pattern.compile("(j2ll_(?:f|cit|cid)_[0-9a-f]+)");
    private static final Pattern LOADER_PATH =
            Pattern.compile("\"generatedLoaders\"\\s*:\\s*\\[\\s*\"([A-Za-z0-9_$/.]+/Loader)\"");

    public DeterminismEvidence compare(CorpusRunResult first, CorpusRunResult second) throws Exception {
        Map<String, String> firstJar = jarEntryHashes(first.pipelineResult().outputJar());
        Map<String, String> secondJar = jarEntryHashes(second.pipelineResult().outputJar());
        List<String> failures = new ArrayList<>();
        if (!firstJar.equals(secondJar)) {
            failures.add("outputJarEntryHashes");
        }
        if (!nativeResourcePaths(firstJar).equals(nativeResourcePaths(secondJar))) {
            failures.add("nativeResourcePaths");
        }
        if (!embeddedNativeSha(first).equals(embeddedNativeSha(second))) {
            failures.add("embeddedNativeSha");
        }
        if (!tokens(first).equals(tokens(second))) {
            failures.add("deterministicTokens");
        }
        if (!first.reportPaths().reports().keySet().equals(second.reportPaths().reports().keySet())) {
            failures.add("reportSet");
        }
        return new DeterminismEvidence(
                failures.isEmpty(),
                firstJar.keySet().stream().toList(),
                nativeResourcePaths(firstJar),
                embeddedNativeSha(first),
                tokens(first),
                failures);
    }

    private Map<String, String> jarEntryHashes(java.nio.file.Path jar) throws Exception {
        TreeMap<String, String> hashes = new TreeMap<>();
        try (JarFile jarFile = new JarFile(jar.toFile(), false)) {
            for (var entry : jarFile.stream().filter(entry -> !entry.isDirectory()).toList()) {
                try (InputStream input = jarFile.getInputStream(entry)) {
                    String hash = normalizedJarEntry(entry.getName())
                            ? "<normalized-native-binary>"
                            : sha256(input.readAllBytes());
                    hashes.put(entry.getName(), hash);
                }
            }
        }
        return hashes;
    }

    private boolean normalizedJarEntry(String entryName) {
        return isNativeLibrary(entryName)
                || entryName.equals("META-INF/j2ll/native-libraries.json");
    }

    private List<String> nativeResourcePaths(Map<String, String> entryHashes) {
        return entryHashes.keySet().stream()
                .filter(this::isNativeLibrary)
                .sorted()
                .toList();
    }

    private boolean isNativeLibrary(String entryName) {
        String lower = entryName.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".dll") || lower.endsWith(".so") || lower.endsWith(".dylib");
    }

    private List<String> embeddedNativeSha(CorpusRunResult result) throws Exception {
        JsonObject packaging = JsonParser.parseString(Files.readString(
                        result.reportPaths().reports().get("packaging-report.json")))
                .getAsJsonObject();
        ArrayList<String> hashes = new ArrayList<>();
        packaging.getAsJsonArray("embeddedLibraries").forEach(element -> {
            JsonObject library = element.getAsJsonObject();
            if (library.has("sha256") && !library.get("sha256").isJsonNull()) {
                hashes.add("<normalized-embedded-native-sha256>");
            }
        });
        return hashes.stream().sorted().toList();
    }

    private Map<String, List<String>> tokens(CorpusRunResult result) throws Exception {
        TreeMap<String, List<String>> tokens = new TreeMap<>();
        String allReports = "";
        for (var path : result.reportPaths().reports().values()) {
            allReports += Files.readString(path) + "\n";
        }
        tokens.put("fallbackId", matches(FALLBACK_ID, allReports));
        tokens.put("stringConstantToken", matches(STRING_TOKEN, allReports));
        tokens.put("hiddenSymbolName", matches(HIDDEN_SYMBOL, allReports));
        tokens.put("generatedLoaderPath", matches(LOADER_PATH, allReports));
        return tokens;
    }

    private List<String> matches(Pattern pattern, String text) {
        return pattern.matcher(text).results()
                .map(match -> match.group(1))
                .distinct()
                .sorted()
                .toList();
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    public record DeterminismEvidence(
            boolean passed,
            List<String> outputJarEntries,
            List<String> nativeResourcePaths,
            List<String> embeddedNativeSha256,
            Map<String, List<String>> stableTokens,
            List<String> failures) {
        public DeterminismEvidence {
            outputJarEntries = List.copyOf(outputJarEntries);
            nativeResourcePaths = List.copyOf(nativeResourcePaths);
            embeddedNativeSha256 = List.copyOf(embeddedNativeSha256);
            stableTokens = Map.copyOf(stableTokens);
            failures = List.copyOf(failures);
        }
    }
}
