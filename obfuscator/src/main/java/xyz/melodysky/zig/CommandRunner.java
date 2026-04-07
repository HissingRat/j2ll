package xyz.melodysky.zig;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

final class CommandRunner {

    interface OutputHandler<T> {
        void onCommandStart(Path logFile, String renderedCommand, boolean mirrorToConsole, T context) throws Exception;

        void onPartialOutput(String text, boolean mirrorToConsole, T context) throws Exception;

        boolean onLine(Path logFile, String line, boolean carriageReturn, boolean mirrorToConsole, T context) throws Exception;
    }

    static final class Result {
        private final int exitCode;
        private final String output;

        Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }

        int exitCode() {
            return exitCode;
        }

        String output() {
            return output;
        }
    }

    private final Path workspaceDirectory;
    private final boolean windows;
    private final String javaHome;

    CommandRunner(Path workspaceDirectory, boolean windows, String javaHome) {
        this.workspaceDirectory = workspaceDirectory;
        this.windows = windows;
        this.javaHome = javaHome;
    }

    <T> Result run(List<String> command, Path logFile, T context, OutputHandler<T> outputHandler) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workspaceDirectory.toFile());
        builder.redirectErrorStream(true);

        if (javaHome != null && !javaHome.isBlank()) {
            builder.environment().put("JAVA_HOME", javaHome);
            String pathKey = windows ? "Path" : "PATH";
            String existingPath = builder.environment().getOrDefault(pathKey, "");
            String separator = windows ? ";" : ":";
            builder.environment().put(pathKey, Path.of(javaHome, "bin") + (existingPath.isEmpty() ? "" : separator + existingPath));
        }

        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        OutputState outputState = new OutputState();
        boolean mirrorToConsole = context == null;

        if (logFile != null && outputHandler != null) {
            outputHandler.onCommandStart(logFile, String.join(" ", command), mirrorToConsole, context);
        }

        try (InputStreamReader input = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
            char[] buffer = new char[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                String chunk = new String(buffer, 0, read);
                output.append(chunk);
                if (logFile != null && outputHandler != null) {
                    streamOutput(logFile, chunk, context, outputState, mirrorToConsole, outputHandler);
                }
            }
        }

        if (logFile != null && outputHandler != null && !outputState.buffer.isEmpty()) {
            flushOutput(logFile, context, outputState, false, mirrorToConsole, outputHandler);
        }

        int exitCode = process.waitFor();
        return new Result(exitCode, output.toString());
    }

    private <T> void streamOutput(Path logFile, String chunk, T context, OutputState outputState,
                                  boolean mirrorToConsole, OutputHandler<T> outputHandler) throws Exception {
        String normalized = chunk.replace("\r\n", "\n");
        for (int i = 0; i < normalized.length(); i++) {
            char current = normalized.charAt(i);
            if (current == '\r') {
                flushOutput(logFile, context, outputState, true, mirrorToConsole, outputHandler);
                continue;
            }
            if (current == '\n') {
                flushOutput(logFile, context, outputState, false, mirrorToConsole, outputHandler);
                continue;
            }
            outputState.buffer.append(current);
        }

        if (mirrorToConsole && !outputState.buffer.isEmpty()) {
            outputHandler.onPartialOutput(outputState.buffer.toString(), true, context);
        }
    }

    private <T> void flushOutput(Path logFile, T context, OutputState outputState, boolean carriageReturn,
                                 boolean mirrorToConsole, OutputHandler<T> outputHandler) throws Exception {
        if (outputState.buffer.isEmpty()) {
            return;
        }

        String line = outputState.buffer.toString();
        outputState.buffer.setLength(0);
        outputHandler.onLine(logFile, line, carriageReturn, mirrorToConsole, context);
    }

    private static class OutputState {
        private final StringBuilder buffer = new StringBuilder();
    }
}
