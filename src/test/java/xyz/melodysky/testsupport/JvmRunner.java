package xyz.melodysky.testsupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class JvmRunner {
    public JvmRunResult run(Path jar, String mainClass, List<String> args) throws IOException, InterruptedException {
        ArrayList<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home")).resolve("bin").resolve("java").toString());
        command.add("--enable-native-access=ALL-UNNAMED");
        command.add("-cp");
        command.add(jar.toString());
        command.add(mainClass);
        command.addAll(args);
        Process process = new ProcessBuilder(command).start();
        boolean exited = process.waitFor(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS);
        if (!exited) {
            process.destroyForcibly();
            throw new IOException("JVM fixture timed out: " + command);
        }
        return new JvmRunResult(
                process.exitValue(),
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
                new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
    }
}
