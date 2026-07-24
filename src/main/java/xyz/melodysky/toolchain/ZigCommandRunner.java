package xyz.melodysky.toolchain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
            try (ExecutorService drains = Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual().name("j2ll-zig-output-", 0).factory())) {
                Future<byte[]> stdoutBytes = drains.submit(() -> process.getInputStream().readAllBytes());
                Future<byte[]> stderrBytes = drains.submit(() -> process.getErrorStream().readAllBytes());
                int exitCode = process.waitFor();
                String stdout = new String(stdoutBytes.get(), StandardCharsets.UTF_8);
                String stderr = new String(stderrBytes.get(), StandardCharsets.UTF_8);
                return new ZigCommandResult(exitCode, stdout, stderr);
            } catch (InterruptedException exception) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException("managed Zig command interrupted: " + String.join(" ", command), exception);
            } catch (ExecutionException exception) {
                process.destroyForcibly();
                throw new IOException(
                        "failed to read managed Zig command output: " + String.join(" ", command),
                        exception.getCause());
            }
        };
    }
}
