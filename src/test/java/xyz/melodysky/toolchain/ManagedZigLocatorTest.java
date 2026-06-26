package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedZigLocatorTest {
    @TempDir
    Path temp;

    @Test
    void reusesExistingManagedZigWithExactVersion() throws Exception {
        Path executable = temp.resolve("zig/zig");
        Files.createDirectories(executable.getParent());
        Files.writeString(executable, "");
        RecordingRunner runner = new RecordingRunner(List.of("0.15.2"));

        ManagedZig zig = locator(runner, failingDownloader(), failingExtractor(), archive()).ensure(temp);

        assertEquals(executable, zig.executable());
        assertEquals("0.15.2", zig.version());
        assertFalse(runner.commands().isEmpty());
    }

    @Test
    void wrongVersionReinstallsFromLocalArchiveBeforeDownload() throws Exception {
        Path executable = temp.resolve("zig/zig");
        Files.createDirectories(executable.getParent());
        Files.writeString(executable, "");
        ZigArchiveMetadata archive = archive();
        Files.writeString(temp.resolve(archive.archiveName()), "local archive");
        RecordingRunner runner = new RecordingRunner(List.of("0.16.0", "0.15.2"));
        AtomicInteger downloads = new AtomicInteger();
        ZigArchiveExtractor extractor = new ZigArchiveExtractor(runner) {
            @Override
            public void extractNormalized(ZigArchiveMetadata metadata, Path archivePath, Path destination) throws IOException {
                Files.createDirectories(destination);
                Files.writeString(destination.resolve("zig"), "");
            }
        };

        ManagedZig zig = locator(
                        runner,
                        (uri, destination) -> downloads.incrementAndGet(),
                        extractor,
                        archive)
                .ensure(temp);

        assertEquals("0.15.2", zig.version());
        assertEquals(0, downloads.get());
    }

    @Test
    void missingLocalArchiveReportsDownloadFailure() {
        IOException error = assertThrows(IOException.class, () -> locator(
                        new RecordingRunner(List.of("0.16.0")),
                        (uri, destination) -> {
                            throw new IOException("network down");
                        },
                        failingExtractor(),
                        archive())
                .ensure(temp));

        assertTrue(error.getMessage().contains("failed to download managed Zig 0.15.2"));
    }

    @Test
    void zipExtractionRejectsPathTraversal() throws Exception {
        Path archive = temp.resolve("zig-test.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("../evil"));
            zip.write("bad".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        IOException error = assertThrows(IOException.class, () -> new ZigArchiveExtractor()
                .extractNormalized(
                        new ZigArchiveMetadata("zig-test.zip", URI.create("https://example.test/zig-test.zip"), true, ""),
                        archive,
                        temp.resolve("zig")));

        assertTrue(error.getMessage().contains("escapes destination"));
    }

    private ManagedZigLocator locator(
            RecordingRunner runner,
            ZigDownloader downloader,
            ZigArchiveExtractor extractor,
            ZigArchiveMetadata metadata) {
        return new ManagedZigLocator(
                new ZigArchiveResolver() {
                    @Override
                    public ZigArchiveMetadata currentHostArchive() {
                        return metadata;
                    }
                },
                downloader,
                extractor,
                ZigArchiveVerifier.boundaryOnly(),
                runner,
                false);
    }

    private ZigArchiveMetadata archive() {
        return new ZigArchiveMetadata(
                "zig-x86_64-linux-0.15.2.tar.xz",
                URI.create("https://ziglang.org/download/0.15.2/zig-x86_64-linux-0.15.2.tar.xz"),
                false,
                ZigArchiveResolver.CHECKSUM_BOUNDARY);
    }

    private ZigDownloader failingDownloader() {
        return (uri, destination) -> {
            throw new AssertionError("download should not be used");
        };
    }

    private ZigArchiveExtractor failingExtractor() {
        return new ZigArchiveExtractor() {
            @Override
            public void extractNormalized(ZigArchiveMetadata metadata, Path archive, Path destination) {
                throw new AssertionError("extract should not be used");
            }
        };
    }

    private static final class RecordingRunner implements ZigCommandRunner {
        private final ArrayList<String> versions;
        private final ArrayList<List<String>> commands = new ArrayList<>();

        private RecordingRunner(List<String> versions) {
            this.versions = new ArrayList<>(versions);
        }

        @Override
        public ZigCommandResult run(List<String> command, Path workingDirectory, Map<String, String> environment) {
            commands.add(List.copyOf(command));
            String version = versions.isEmpty() ? "0.15.2" : versions.remove(0);
            return new ZigCommandResult(0, version + "\n", "");
        }

        private List<List<String>> commands() {
            return commands;
        }
    }
}
