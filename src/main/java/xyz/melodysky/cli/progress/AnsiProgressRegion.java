package xyz.melodysky.cli.progress;

import java.io.PrintStream;
import java.util.List;
import java.util.Objects;

final class AnsiProgressRegion {
    private final PrintStream output;
    private final int terminalWidth;
    private List<String> activeLines = List.of();

    AnsiProgressRegion(PrintStream output, int terminalWidth) {
        this.output = Objects.requireNonNull(output, "output");
        this.terminalWidth = Math.max(1, terminalWidth);
    }

    void update(List<String> lines) {
        clearInternal();
        activeLines = fit(lines);
        render(activeLines);
        output.flush();
    }

    void complete(List<String> lines) {
        clearInternal();
        List<String> completedLines = fit(lines);
        render(completedLines);
        if (!completedLines.isEmpty()) {
            output.println();
        }
        activeLines = List.of();
        output.flush();
    }

    void clear() {
        clearInternal();
        output.flush();
    }

    private void clearInternal() {
        if (activeLines.isEmpty()) {
            return;
        }
        for (int index = 0; index < activeLines.size(); index++) {
            output.print("\r\u001B[2K");
            if (index < activeLines.size() - 1) {
                output.print("\u001B[1A");
            }
        }
        activeLines = List.of();
    }

    private void render(List<String> lines) {
        for (int index = 0; index < lines.size(); index++) {
            if (index > 0) {
                output.print('\n');
            }
            output.print('\r');
            output.print(lines.get(index));
        }
    }

    private List<String> fit(List<String> lines) {
        return lines.stream()
                .map(line -> TerminalText.fitLine(line, terminalWidth))
                .toList();
    }
}
