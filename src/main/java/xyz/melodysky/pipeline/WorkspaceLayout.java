package xyz.melodysky.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class WorkspaceLayout {
    private final Path root;

    public WorkspaceLayout(Path root) {
        this.root = Objects.requireNonNull(root, "root").normalize();
    }

    public Path root() {
        return root;
    }

    public Path reportsDirectory() {
        return root.resolve("reports");
    }

    public Path outputJar(Path inputJar) {
        Path fileName = Objects.requireNonNull(inputJar, "inputJar").getFileName();
        if (fileName == null) {
            throw new IllegalArgumentException("inputJar must have a file name");
        }
        return root.resolve(fileName);
    }

    public Path failedOutputJar() {
        return root.resolve("config-failed.jar");
    }

    public void createDirectories() throws IOException {
        Files.createDirectories(reportsDirectory());
        Files.createDirectories(root.resolve("native"));
        Files.createDirectories(root.resolve("intermediates/classes"));
        Files.createDirectories(root.resolve("intermediates/runtime"));
        Files.createDirectories(root.resolve("intermediates/dumps"));
        Files.createDirectories(root.resolve("logs"));
    }
}
