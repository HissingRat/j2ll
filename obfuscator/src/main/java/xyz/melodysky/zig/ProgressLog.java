package xyz.melodysky.zig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;

final class ProgressLog {

    private List<String> consoleProgressLines = List.of();

    ProgressLog(boolean windows) {
    }

    synchronized void appendLog(Path logFile, String text) throws Exception {
        clearConsoleProgressInternal();
        Files.writeString(logFile, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        System.out.print(text);
        renderConsoleProgressInternal();
        System.out.flush();
    }

    synchronized void appendFileLog(Path logFile, String text) throws Exception {
        Files.writeString(logFile, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    synchronized void updateConsoleProgress(String text) {
        updateConsoleProgressLines(List.of(text));
    }

    synchronized void updateConsoleProgressLines(List<String> lines) {
        clearConsoleProgressInternal();
        consoleProgressLines = lines.stream()
                .map(line -> line.replace("\r", "").replace("\n", ""))
                .toList();
        renderConsoleProgressInternal();
        System.out.flush();
    }

    synchronized void clearConsoleProgress() {
        clearConsoleProgressInternal();
        System.out.flush();
    }

    String formatDownloadProgress(long downloaded, long totalBytes, int percent, long startedAt, long now) {
        double seconds = Math.max(0.001, (now - startedAt) / 1000.0);
        String speed = formatSpeed(downloaded / seconds);
        if (totalBytes > 0 && percent >= 0) {
            return String.format(Locale.ROOT,
                    "Download progress: %s %d%% (%s / %s, %s)",
                    formatProgressBar(percent, 100, 28),
                    percent, formatSize(downloaded), formatSize(totalBytes), speed);
        }
        return String.format(Locale.ROOT,
                "Download progress: %s (%s)",
                formatSize(downloaded), speed);
    }

    String formatProgressBar(long current, long total, int width) {
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

    private String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
        }
        return String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private String formatSpeed(double bytesPerSecond) {
        if (bytesPerSecond < 1024) {
            return String.format(Locale.ROOT, "%.0f B/s", bytesPerSecond);
        }
        if (bytesPerSecond < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB/s", bytesPerSecond / 1024.0);
        }
        if (bytesPerSecond < 1024 * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MB/s", bytesPerSecond / (1024.0 * 1024.0));
        }
        return String.format(Locale.ROOT, "%.2f GB/s", bytesPerSecond / (1024.0 * 1024.0 * 1024.0));
    }

    private void clearConsoleProgressInternal() {
        if (consoleProgressLines.isEmpty()) {
            return;
        }

        for (int i = 0; i < consoleProgressLines.size(); i++) {
            System.out.print("\r\u001B[2K");
            if (i < consoleProgressLines.size() - 1) {
                System.out.print("\u001B[1A");
            }
        }
        consoleProgressLines = List.of();
    }

    private void renderConsoleProgressInternal() {
        if (consoleProgressLines.isEmpty()) {
            return;
        }
        for (int i = 0; i < consoleProgressLines.size(); i++) {
            if (i > 0) {
                System.out.print("\n");
            }
            System.out.print("\r" + consoleProgressLines.get(i));
        }
    }
}
