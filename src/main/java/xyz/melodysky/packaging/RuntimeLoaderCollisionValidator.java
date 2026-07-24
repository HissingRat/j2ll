package xyz.melodysky.packaging;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticStage;

public final class RuntimeLoaderCollisionValidator {
    public List<Diagnostic> validate(Path inputJar, RuntimeLoaderPlan plan) throws IOException {
        ArrayList<Diagnostic> diagnostics = new ArrayList<>();
        String entryName = plan.entryName();
        try (JarFile jarFile = new JarFile(inputJar.toFile(), false)) {
            long baseEntries = jarFile.stream()
                    .filter(entry -> !entry.isDirectory())
                    .filter(entry -> entry.getName().equals(entryName))
                    .count();
            if (baseEntries > 0) {
                diagnostics.add(Diagnostic.error(
                                DiagnosticStage.PACKAGING,
                                PackagingDiagnostics.GENERATED_RUNTIME_LOADER_ENTRY_COLLISION,
                                "input JAR already contains reserved runtime Loader entry " + entryName)
                        .withDecision("failed"));
            }
            List<String> versionedEntries = jarFile.stream()
                    .filter(entry -> !entry.isDirectory())
                    .map(entry -> entry.getName())
                    .filter(name -> isVersionedLoaderEntry(name, entryName))
                    .sorted()
                    .toList();
            if (!versionedEntries.isEmpty()) {
                diagnostics.add(Diagnostic.error(
                                DiagnosticStage.PACKAGING,
                                PackagingDiagnostics.GENERATED_RUNTIME_LOADER_VERSIONED_SHADOW,
                                "multi-release entries would shadow generated runtime Loader: " + versionedEntries)
                        .withDecision("failed"));
            }
        }
        return List.copyOf(diagnostics);
    }

    private boolean isVersionedLoaderEntry(String candidate, String loaderEntryName) {
        String prefix = "META-INF/versions/";
        if (!candidate.startsWith(prefix)) {
            return false;
        }
        int versionEnd = candidate.indexOf('/', prefix.length());
        if (versionEnd < 0
                || versionEnd == prefix.length()
                || !candidate.substring(prefix.length(), versionEnd).chars().allMatch(Character::isDigit)) {
            return false;
        }
        return candidate.substring(versionEnd + 1).equals(loaderEntryName);
    }
}
