package xyz.melodysky.toolchain.symbols;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import xyz.melodysky.toolchain.TargetTriple;

public final class NativeSymbolInspector {
    public List<String> exportedSymbols(TargetTriple target, Path libraryPath) {
        if (target.isWindows()) {
            return List.of();
        }
        ArrayList<String> command = new ArrayList<>();
        command.add("nm");
        if (target == TargetTriple.MACOS_ARM64 || target == TargetTriple.MACOS_X64) {
            command.add("-gU");
        } else {
            command.add("-D");
            command.add("--defined-only");
        }
        command.add(libraryPath.toString());
        try {
            Process process = new ProcessBuilder(command).start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                return List.of();
            }
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return stdout.lines()
                    .map(line -> symbolFromNmLine(target, line))
                    .filter(symbol -> !symbol.isBlank())
                    .distinct()
                    .sorted(Comparator.naturalOrder())
                    .toList();
        } catch (IOException exception) {
            return List.of();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    private String symbolFromNmLine(TargetTriple target, String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.toLowerCase(Locale.ROOT).contains(" no symbols")) {
            return "";
        }
        String[] parts = trimmed.split("\\s+");
        String symbol = parts[parts.length - 1];
        if ((target == TargetTriple.MACOS_ARM64 || target == TargetTriple.MACOS_X64) && symbol.startsWith("_")) {
            symbol = symbol.substring(1);
        }
        return symbol;
    }
}
