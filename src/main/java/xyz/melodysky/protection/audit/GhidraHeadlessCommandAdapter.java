package xyz.melodysky.protection.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Optional command adapter for a separately gated Ghidra reverse-analysis
 * suite. Normal unit tests and the lightweight audit harness do not require
 * Ghidra.
 */
public final class GhidraHeadlessCommandAdapter {
    public Optional<List<String>> command(GhidraHeadlessRequest request) {
        Path executable = executable(request.ghidraHome());
        if (!Files.isRegularFile(executable)) {
            return Optional.empty();
        }
        ArrayList<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.add(request.projectDirectory().toString());
        command.add(request.projectName());
        command.add("-import");
        command.add(request.nativeLibrary().toString());
        command.add("-scriptPath");
        command.add(request.scriptDirectory().toString());
        command.add("-postScript");
        command.add(request.postScript());
        command.addAll(request.scriptArguments());
        command.add("-deleteProject");
        return Optional.of(List.copyOf(command));
    }

    private Path executable(Path ghidraHome) {
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
        return ghidraHome.resolve("support")
                .resolve(windows ? "analyzeHeadless.bat" : "analyzeHeadless");
    }
}
