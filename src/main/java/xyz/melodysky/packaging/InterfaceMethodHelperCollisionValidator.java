package xyz.melodysky.packaging;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticStage;

/** Rejects input entries that would collide with generated interface helper classes. */
public final class InterfaceMethodHelperCollisionValidator {
    public List<Diagnostic> validate(
            Path inputJar,
            List<MethodRewriteDecision> decisions) throws IOException {
        Set<String> helperEntries = decisions.stream()
                .filter(decision -> decision.strategy() == MethodRewriteStrategy.INTERFACE_METHOD_STUB)
                .map(decision -> decision.registrationOwner() + ".class")
                .collect(Collectors.toUnmodifiableSet());
        if (helperEntries.isEmpty()) {
            return List.of();
        }
        ArrayList<Diagnostic> diagnostics = new ArrayList<>();
        try (JarFile jarFile = new JarFile(inputJar.toFile(), false)) {
            List<String> names = jarFile.stream()
                    .filter(entry -> !entry.isDirectory())
                    .map(entry -> entry.getName())
                    .toList();
            helperEntries.stream().sorted().forEach(helperEntry -> {
                if (names.contains(helperEntry)) {
                    diagnostics.add(Diagnostic.error(
                                    DiagnosticStage.PACKAGING,
                                    PackagingDiagnostics.GENERATED_INTERFACE_HELPER_ENTRY_COLLISION,
                                    "input JAR already contains reserved interface helper entry " + helperEntry)
                            .withDecision("failed"));
                }
                List<String> shadows = names.stream()
                        .filter(name -> isVersionedEntry(name, helperEntry))
                        .sorted()
                        .toList();
                if (!shadows.isEmpty()) {
                    diagnostics.add(Diagnostic.error(
                                    DiagnosticStage.PACKAGING,
                                    PackagingDiagnostics.GENERATED_INTERFACE_HELPER_VERSIONED_SHADOW,
                                    "multi-release entries would shadow generated interface helper: " + shadows)
                            .withDecision("failed"));
                }
            });
        }
        return List.copyOf(diagnostics);
    }

    private boolean isVersionedEntry(String candidate, String entryName) {
        String prefix = "META-INF/versions/";
        if (!candidate.startsWith(prefix)) {
            return false;
        }
        int versionEnd = candidate.indexOf('/', prefix.length());
        return versionEnd > prefix.length()
                && candidate.substring(prefix.length(), versionEnd).chars().allMatch(Character::isDigit)
                && candidate.substring(versionEnd + 1).equals(entryName);
    }
}
