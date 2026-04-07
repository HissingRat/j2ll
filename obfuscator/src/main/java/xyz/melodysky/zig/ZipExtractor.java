package xyz.melodysky.zig;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class ZipExtractor {

    private static final int FILE_COPY_BUFFER_SIZE = 64 * 1024;

    private ZipExtractor() {
    }

    static void extract(Path archive, Path extractDir) throws Exception {
        try (ZipFile zipFile = new ZipFile(archive.toFile())) {
            var entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path target = resolveEntry(extractDir, entry);
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }

                Path parent = target.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }

                try (InputStream input = new BufferedInputStream(zipFile.getInputStream(entry));
                     OutputStream output = new BufferedOutputStream(Files.newOutputStream(target,
                             StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE),
                             FILE_COPY_BUFFER_SIZE)) {
                    input.transferTo(output);
                }
            }
        }
    }

    private static Path resolveEntry(Path extractDir, ZipEntry entry) {
        try {
            Path target = extractDir.resolve(entry.getName()).normalize();
            if (!target.startsWith(extractDir)) {
                throw new IllegalStateException("Refusing to extract Zig archive entry outside " + extractDir + ": " + entry.getName());
            }
            return target;
        } catch (InvalidPathException exception) {
            throw new IllegalStateException("Invalid path in Zig archive: " + entry.getName(), exception);
        }
    }
}
