package xyz.melodysky.toolchain;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ZigBuildException extends IOException {
    private final List<TargetTriple> failedTargets;
    private final Path logFile;
    private final String logTail;

    private ZigBuildException(
            String message,
            List<TargetTriple> failedTargets,
            Path logFile,
            String logTail,
            IOException cause) {
        super(message, cause);
        this.failedTargets = List.copyOf(failedTargets);
        this.logFile = logFile;
        this.logTail = logTail;
    }

    public static ZigBuildException from(
            NativeBuildPlan plan,
            ZigBuildWorkspace workspace,
            IOException cause) {
        List<TargetTriple> failed = plan.units().stream()
                .filter(unit -> !Files.isRegularFile(unit.outputPath()))
                .map(NativeBuildUnit::target)
                .toList();
        if (failed.isEmpty()) {
            failed = plan.units().stream().map(NativeBuildUnit::target).toList();
        }
        Path log = workspace.logsDirectory().resolve("zig-build.log");
        String tail = readDiagnosticExcerpt(log);
        String message = "managed Zig matrix build failed for targets "
                + failed.stream().map(TargetTriple::directoryName).toList()
                + "; log=" + log.toAbsolutePath()
                + (tail.isBlank() ? "" : System.lineSeparator() + tail);
        return new ZigBuildException(message, failed, log, tail, cause);
    }

    public List<TargetTriple> failedTargets() {
        return failedTargets;
    }

    public Path logFile() {
        return logFile;
    }

    public String logTail() {
        return logTail;
    }

    private static String readDiagnosticExcerpt(Path log) {
        if (!Files.isRegularFile(log)) {
            return "";
        }
        ArrayList<String> errors = new ArrayList<>();
        ArrayDeque<String> tail = new ArrayDeque<>();
        try (BufferedReader reader = Files.newBufferedReader(log, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String bounded = line.length() > 1000 ? line.substring(0, 1000) + "…" : line;
                String lower = bounded.toLowerCase(Locale.ROOT);
                if (errors.size() < 24
                        && (lower.contains("error:")
                                || lower.contains("compilation errors")
                                || lower.contains("build summary"))) {
                    errors.add(bounded);
                }
                tail.addLast(bounded);
                if (tail.size() > 24) {
                    tail.removeFirst();
                }
            }
            ArrayList<String> excerpt = new ArrayList<>();
            if (!errors.isEmpty()) {
                excerpt.add("Zig error excerpt:");
                excerpt.addAll(errors);
            }
            if (!tail.isEmpty()) {
                excerpt.add("Zig log tail:");
                excerpt.addAll(tail);
            }
            return String.join(System.lineSeparator(), excerpt);
        } catch (IOException ignored) {
            return "";
        }
    }
}
