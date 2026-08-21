package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
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
        assertTrue(zig.bootstrapEvents().stream()
                .anyMatch(event -> event.code().equals("FOUND_MANAGED_ZIG")));
        assertFalse(runner.commands().isEmpty());
    }

    @Test
    void wrongVersionReinstallsFromLocalArchiveBeforeDownload() throws Exception {
        Path executable = temp.resolve("zig/zig");
        Files.createDirectories(executable.getParent());
        Files.writeString(executable, "");
        byte[] archiveBytes = "local archive".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ZigArchiveMetadata archive = archive(archiveBytes);
        Files.write(temp.resolve(archive.archiveName()), archiveBytes);
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
        assertTrue(zig.bootstrapEvents().stream()
                .anyMatch(event -> event.code().equals("WRONG_VERSION_REINSTALL")));
        assertTrue(zig.bootstrapEvents().stream()
                .anyMatch(event -> event.code().equals("LOCAL_ARCHIVE_USED")));
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
    void corruptLocalArchiveChecksumFailsBeforeExtraction() throws Exception {
        ZigArchiveMetadata archive = archive("expected archive".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.writeString(temp.resolve(archive.archiveName()), "corrupt archive");

        IOException error = assertThrows(IOException.class, () -> locator(
                        new RecordingRunner(List.of("0.16.0")),
                        failingDownloader(),
                        failingExtractor(),
                        archive,
                        ZigArchiveVerifier.sha256())
                .ensure(temp));

        assertTrue(error.getMessage().contains("checksum mismatch"));
    }

    @Test
    void downloadedArchiveChecksumFailureDoesNotExtract() throws Exception {
        byte[] expected = "expected archive".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ZigArchiveMetadata archive = archive(expected);

        IOException error = assertThrows(IOException.class, () -> locator(
                        new RecordingRunner(List.of("0.16.0")),
                        (uri, destination) -> Files.writeString(destination, "downloaded corrupt archive"),
                        failingExtractor(),
                        archive,
                        ZigArchiveVerifier.sha256())
                .ensure(temp));

        assertTrue(error.getMessage().contains("checksum mismatch"));
    }

    @Test
    void correctLocalArchiveChecksumInstallsAndReportsArchiveEvents() throws Exception {
        byte[] archiveBytes = "valid local archive".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ZigArchiveMetadata archive = archive(archiveBytes);
        Files.write(temp.resolve(archive.archiveName()), archiveBytes);
        RecordingRunner runner = new RecordingRunner(List.of("0.15.2"));
        ZigArchiveExtractor extractor = new ZigArchiveExtractor(runner) {
            @Override
            public void extractNormalized(ZigArchiveMetadata metadata, Path archivePath, Path destination) throws IOException {
                Files.createDirectories(destination);
                Files.writeString(destination.resolve("zig"), "");
            }
        };

        ManagedZig zig = locator(runner, failingDownloader(), extractor, archive, ZigArchiveVerifier.sha256())
                .ensure(temp);

        assertEquals("0.15.2", zig.version());
        ManagedZigBootstrapEvent verified = zig.bootstrapEvents().stream()
                .filter(event -> event.code().equals("ARCHIVE_CHECKSUM_VERIFIED"))
                .findFirst()
                .orElseThrow();
        assertEquals(archive.archiveName(), verified.archiveName());
        assertEquals(archive.expectedSha256(), verified.archiveSha256());
        assertEquals("verified", verified.checksumStatus());
        assertEquals("notVerifiedBoundary", verified.signatureStatus());
        assertEquals("localArchive", verified.source());
    }

    @Test
    void concurrentEnsuresDownloadExtractAndPublishOnce() throws Exception {
        byte[] archiveBytes = "shared valid archive".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ZigArchiveMetadata archive = archive(archiveBytes);
        AtomicInteger downloads = new AtomicInteger();
        AtomicInteger extractions = new AtomicInteger();
        ZigDownloader downloader = (uri, destination) -> {
            downloads.incrementAndGet();
            Files.write(destination, archiveBytes);
        };
        ZigArchiveExtractor extractor = new ZigArchiveExtractor() {
            @Override
            public void extractNormalized(
                    ZigArchiveMetadata metadata,
                    Path archivePath,
                    Path destination) throws IOException {
                extractions.incrementAndGet();
                Files.createDirectories(destination);
                Files.writeString(destination.resolve("zig"), "");
            }
        };
        ManagedZigLocator first = locator(
                new RecordingRunner(List.of("0.15.2")),
                downloader,
                extractor,
                archive,
                ZigArchiveVerifier.sha256());
        ManagedZigLocator second = locator(
                new RecordingRunner(List.of("0.15.2")),
                downloader,
                extractor,
                archive,
                ZigArchiveVerifier.sha256());

        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> first.ensure(temp));
            var two = executor.submit(() -> second.ensure(temp));
            assertEquals("0.15.2", one.get().version());
            assertEquals("0.15.2", two.get().version());
        }

        assertEquals(1, downloads.get());
        assertEquals(1, extractions.get());
        assertArrayEquals(archiveBytes, Files.readAllBytes(temp.resolve(archive.archiveName())));
        try (var files = Files.list(temp)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".downloading")));
        }
    }

    @Test
    void unsupportedHostArchiveMetadataFailsClearly() {
        IOException error = assertThrows(IOException.class, () -> new ManagedZigLocator(
                        new ZigArchiveResolver() {
                            @Override
                            public ZigArchiveMetadata currentHostArchive() {
                                throw new IllegalStateException("unsupported host OS for managed Zig: plan9");
                            }
                        },
                        failingDownloader(),
                        failingExtractor(),
                        ZigArchiveVerifier.sha256(),
                        new RecordingRunner(List.of("0.16.0")),
                        false)
                .ensure(temp));

        assertTrue(error.getMessage().contains("unsupported host archive metadata"));
        assertTrue(error.getMessage().contains("plan9"));
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

    @Test
    void failedExtractionDoesNotDeleteExistingInstall() throws Exception {
        Path archive = temp.resolve("zig-test.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("../evil"));
            zip.write("bad".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Path destination = temp.resolve("installed-zig");
        Files.createDirectories(destination);
        Files.writeString(destination.resolve("zig"), "existing");

        assertThrows(IOException.class, () -> new ZigArchiveExtractor()
                .extractNormalized(
                        new ZigArchiveMetadata("zig-test.zip", URI.create("https://example.test/zig-test.zip"), true, ""),
                        archive,
                        destination));

        assertEquals("existing", Files.readString(destination.resolve("zig")));
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

    private ManagedZigLocator locator(
            RecordingRunner runner,
            ZigDownloader downloader,
            ZigArchiveExtractor extractor,
            ZigArchiveMetadata metadata,
            ZigArchiveVerifier verifier) {
        return new ManagedZigLocator(
                new ZigArchiveResolver() {
                    @Override
                    public ZigArchiveMetadata currentHostArchive() {
                        return metadata;
                    }
                },
                downloader,
                extractor,
                verifier,
                runner,
                false);
    }

    private ZigArchiveMetadata archive() {
        return archive("boundary archive".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private ZigArchiveMetadata archive(byte[] bytes) {
        return new ZigArchiveMetadata(
                "zig-x86_64-linux-0.15.2.tar.xz",
                URI.create("https://ziglang.org/download/0.15.2/zig-x86_64-linux-0.15.2.tar.xz"),
                false,
                sha256(bytes),
                "notVerifiedBoundary");
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
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
