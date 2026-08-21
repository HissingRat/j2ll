package xyz.melodysky.toolchain;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ZigArchiveExtractor {
    private final ZigCommandRunner runner;

    public ZigArchiveExtractor() {
        this(ZigCommandRunner.process());
    }

    public ZigArchiveExtractor(ZigCommandRunner runner) {
        this.runner = runner;
    }

    public void extractNormalized(ZigArchiveMetadata metadata, Path archive, Path destination) throws IOException {
        Path parent = destination.toAbsolutePath().normalize().getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempDirectory(parent, destination.getFileName() + ".extracting-");
        try {
            if (metadata.zipArchive()) {
                extractZip(archive, temporary);
            } else {
                extractTarXz(archive, temporary);
            }
            Path root = singleExtractedRoot(temporary);
            Path staged = parent.resolve(destination.getFileName() + ".staged-" + temporary.getFileName());
            Files.move(root, staged);
            try {
                deleteRecursively(destination);
                publish(staged, destination);
            } finally {
                deleteRecursively(staged);
            }
            Path executable = destination.resolve(isWindowsArchive(metadata) ? "zig.exe" : "zig");
            if (Files.exists(executable)) {
                executable.toFile().setExecutable(true);
            }
        } finally {
            deleteRecursively(temporary);
        }
    }

    private void publish(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(source, destination);
        }
    }

    private void extractZip(Path archive, Path destination) throws IOException {
        try (InputStream raw = Files.newInputStream(archive);
                ZipInputStream zip = new ZipInputStream(raw)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path target = safeArchiveEntry(destination, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                }
                zip.closeEntry();
            }
        }
    }

    private void extractTarXz(Path archive, Path destination) throws IOException {
        ZigCommandResult list = runner.run(
                List.of("tar", "-tf", archive.toAbsolutePath().toString()),
                null,
                Map.of());
        if (list.exitCode() != 0) {
            throw new IOException("failed to list managed Zig archive: " + list.combinedOutput());
        }
        for (String line : list.stdout().lines().toList()) {
            if (!line.isBlank()) {
                safeArchiveEntry(destination, line);
            }
        }
        ZigCommandResult extract = runner.run(
                List.of("tar", "-xf", archive.toAbsolutePath().toString(), "-C", destination.toAbsolutePath().toString()),
                null,
                Map.of());
        if (extract.exitCode() != 0) {
            throw new IOException("failed to extract managed Zig archive: " + extract.combinedOutput());
        }
    }

    private Path safeArchiveEntry(Path destination, String rawName) throws IOException {
        Path relative = Path.of(rawName).normalize();
        if (relative.isAbsolute() || relative.startsWith("..") || rawName.contains("\\..\\")) {
            throw new IOException("managed Zig archive entry escapes destination: " + rawName);
        }
        Path target = destination.resolve(relative).normalize();
        ensureInside(destination, target);
        return target;
    }

    private void ensureInside(Path root, Path child) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedChild = child.toAbsolutePath().normalize();
        if (!normalizedChild.startsWith(normalizedRoot)) {
            throw new IOException("managed Zig archive entry escapes destination: " + child);
        }
    }

    private Path singleExtractedRoot(Path directory) throws IOException {
        try (var stream = Files.list(directory).filter(Files::isDirectory)) {
            List<Path> roots = stream.toList();
            if (roots.size() != 1) {
                throw new IOException("unexpected managed Zig archive layout in " + directory);
            }
            return roots.get(0);
        }
    }

    private boolean isWindowsArchive(ZigArchiveMetadata metadata) {
        return metadata.archiveName().contains("-windows-");
    }

    private void deleteRecursively(Path path) throws IOException {
        if (path == null || Files.notExists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            for (Path entry : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }
}
