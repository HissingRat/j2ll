package xyz.melodysky.console;

import org.fusesource.jansi.AnsiConsole;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ConsoleProgressDisplay {

    private static volatile boolean ansiConsoleInitialized = false;
    private static final int FALLBACK_MAX_WIDTH = 96;

    private final boolean ansiCapable;
    private List<String> lines = List.of();
    private int fallbackPrintedWidth;

    public ConsoleProgressDisplay() {
        ensureAnsiConsoleInitialized();
        this.ansiCapable = detectAnsiCapableConsole();
    }

    public synchronized void updateLines(List<String> nextLines) {
        List<String> sanitized = sanitizeLines(nextLines);
        if (!ansiCapable) {
            renderFallbackLine(sanitized);
            return;
        }
        clearInternal();
        lines = truncateLinesToWidth(sanitized, ansiConsoleWidth());
        renderInternal();
        System.out.flush();
    }

    public synchronized void clear() {
        if (!ansiCapable) {
            clearFallbackLine();
            System.out.flush();
            return;
        }
        clearInternal();
        System.out.flush();
    }

    public synchronized void completeLines(List<String> nextLines) {
        List<String> sanitized = sanitizeLines(nextLines);
        if (!ansiCapable) {
            clearFallbackLine();
            int width = fallbackConsoleWidth();
            for (String line : sanitized) {
                System.out.print(truncateToWidth(line, width) + System.lineSeparator());
            }
            fallbackPrintedWidth = 0;
            System.out.flush();
            return;
        }
        clearInternal();
        lines = truncateLinesToWidth(sanitized, ansiConsoleWidth());
        renderInternal();
        System.out.print(System.lineSeparator());
        lines = List.of();
        System.out.flush();
    }

    public String formatProgressBar(long current, long total, int width) {
        if (total <= 0) {
            return "[" + "-".repeat(Math.max(1, width)) + "]";
        }

        int safeWidth = Math.max(4, width);
        long clampedCurrent = Math.max(0, Math.min(current, total));
        int filled = (int) Math.min(safeWidth, clampedCurrent * safeWidth / total);

        if (filled <= 0) {
            return "[" + " ".repeat(safeWidth) + "]";
        }
        if (filled >= safeWidth) {
            return "[" + "=".repeat(safeWidth) + "]";
        }
        return "[" + "=".repeat(Math.max(0, filled - 1)) + ">" + " ".repeat(safeWidth - filled) + "]";
    }

    private void clearInternal() {
        if (lines.isEmpty()) {
            return;
        }
        for (int i = 0; i < lines.size(); i++) {
            System.out.print("\r\u001B[2K");
            if (i < lines.size() - 1) {
                System.out.print("\u001B[1A");
            }
        }
        lines = List.of();
    }

    private void renderInternal() {
        if (lines.isEmpty()) {
            return;
        }
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                System.out.print("\n");
            }
            System.out.print("\r" + lines.get(i));
        }
    }

    private void renderFallbackLine(List<String> sanitizedLines) {
        String summary = summarizeFallbackLines(sanitizedLines, fallbackConsoleWidth());
        int visibleLength = summary.length();
        int padding = Math.max(0, fallbackPrintedWidth - visibleLength);
        System.out.print("\r" + summary + " ".repeat(padding));
        fallbackPrintedWidth = Math.max(fallbackPrintedWidth, visibleLength);
        System.out.flush();
    }

    private void clearFallbackLine() {
        if (fallbackPrintedWidth <= 0) {
            return;
        }
        System.out.print("\r" + " ".repeat(fallbackPrintedWidth) + "\r");
        fallbackPrintedWidth = 0;
    }

    private boolean detectAnsiCapableConsole() {
        return isAnsiCapableConsole(
                System.console() != null && System.console().writer() != null,
                System.getProperty("os.name", ""),
                System.getenv(),
                ansiConsoleInitialized
        );
    }

    static boolean isAnsiCapableConsole(boolean hasConsole, String osName, Map<String, String> env, boolean ansiInitialized) {
        if (!hasConsole) {
            return false;
        }
        String normalizedOsName = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (!normalizedOsName.contains("win")) {
            return true;
        }
        if (ansiInitialized) {
            return true;
        }
        if (hasNonBlankEnv(env, "WT_SESSION") || hasNonBlankEnv(env, "ANSICON")) {
            return true;
        }
        String conEmuAnsi = env.get("ConEmuANSI");
        if (conEmuAnsi != null && "ON".equalsIgnoreCase(conEmuAnsi.trim())) {
            return true;
        }
        String term = env.get("TERM");
        return term != null && term.toLowerCase(Locale.ROOT).contains("xterm");
    }

    private static boolean hasNonBlankEnv(Map<String, String> env, String name) {
        String value = env.get(name);
        return value != null && !value.isBlank();
    }

    private void ensureAnsiConsoleInitialized() {
        if (ansiConsoleInitialized || System.console() == null) {
            return;
        }
        synchronized (ConsoleProgressDisplay.class) {
            if (ansiConsoleInitialized) {
                return;
            }
            AnsiConsole.systemInstall();
            ansiConsoleInitialized = true;
        }
    }

    private int fallbackConsoleWidth() {
        return boundedConsoleWidth(FALLBACK_MAX_WIDTH);
    }

    private int ansiConsoleWidth() {
        return boundedConsoleWidth(240);
    }

    private int boundedConsoleWidth(int maxWidth) {
        int width = 120;
        try {
            int jansiWidth = AnsiConsole.getTerminalWidth();
            if (jansiWidth > 20) {
                width = jansiWidth;
            }
        } catch (Throwable ignored) {
        }
        return Math.max(40, Math.min(maxWidth, width - 1));
    }

    private String summarizeFallbackLines(List<String> sanitizedLines, int width) {
        if (sanitizedLines.isEmpty()) {
            return "";
        }
        if (sanitizedLines.size() == 1) {
            return truncateToWidth(sanitizedLines.getFirst(), width);
        }
        int separatorWidth = 3 * (sanitizedLines.size() - 1);
        int perLineWidth = Math.max(12, (width - separatorWidth) / sanitizedLines.size());
        String summary = sanitizedLines.stream()
                .map(line -> truncateToWidth(line, perLineWidth))
                .reduce((left, right) -> left + " | " + right)
                .orElse("");
        return truncateToWidth(summary, width);
    }

    String summarizeFallbackLinesForTest(List<String> sanitizedLines, int width) {
        return summarizeFallbackLines(sanitizedLines, width);
    }

    List<String> truncateLinesToWidthForTest(List<String> sanitizedLines, int width) {
        return truncateLinesToWidth(sanitizedLines, width);
    }

    private List<String> sanitizeLines(List<String> nextLines) {
        return nextLines.stream()
                .map(line -> line.replace("\r", "").replace("\n", ""))
                .toList();
    }

    private List<String> truncateLinesToWidth(List<String> nextLines, int width) {
        return nextLines.stream()
                .map(line -> truncateToWidth(line, width))
                .toList();
    }

    private String truncateToWidth(String text, int width) {
        if (text.length() <= width) {
            return text;
        }
        if (width <= 3) {
            return text.substring(0, Math.max(0, width));
        }
        return text.substring(0, width - 3) + "...";
    }
}
