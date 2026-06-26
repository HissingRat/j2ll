package xyz.melodysky.toolchain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public interface ZigCommandRunner {
    ZigCommandResult run(List<String> command, Path workingDirectory, Map<String, String> environment) throws IOException;

    static ZigCommandRunner process() {
        return (command, workingDirectory, environment) -> {
            ProcessBuilder builder = new ProcessBuilder(command);
            if (workingDirectory != null) {
                builder.directory(workingDirectory.toFile());
            }
            if (environment != null) {
                builder.environment().putAll(environment);
            }
            Process process = builder.start();
            try {
                int exitCode = process.waitFor();
                String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                return new ZigCommandResult(exitCode, stdout, stderr);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("managed Zig command interrupted: " + String.join(" ", command), exception);
            }
        };
    }
}
