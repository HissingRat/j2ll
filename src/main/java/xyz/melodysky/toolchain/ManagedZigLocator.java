package xyz.melodysky.toolchain;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ManagedZigLocator {
    private static final ConcurrentHashMap<Path, Object> PROCESS_LOCKS = new ConcurrentHashMap<>();

    private final ZigArchiveResolver archiveResolver;
    private final ZigDownloader downloader;
    private final ZigArchiveExtractor extractor;
    private final ZigArchiveVerifier verifier;
    private final ZigCommandRunner runner;
    private final boolean windows;

    public ManagedZigLocator() {
        this(
                new ZigArchiveResolver(),
                ZigDownloader.http(),
                new ZigArchiveExtractor(),
                ZigArchiveVerifier.sha256(),
                ZigCommandRunner.process(),
                isCurrentWindows());
    }

    public ManagedZigLocator(
            ZigArchiveResolver archiveResolver,
            ZigDownloader downloader,
            ZigArchiveExtractor extractor,
            ZigArchiveVerifier verifier,
            ZigCommandRunner runner,
            boolean windows) {
        this.archiveResolver = archiveResolver;
        this.downloader = downloader;
        this.extractor = extractor;
        this.verifier = verifier;
        this.runner = runner;
        this.windows = windows;
    }

    public ManagedZig ensure(Path j2llHome) throws IOException {
        Path home = j2llHome.toAbsolutePath().normalize();
        Files.createDirectories(home);
        Object processLock = PROCESS_LOCKS.computeIfAbsent(home, ignored -> new Object());
        synchronized (processLock) {
            try (FileChannel channel = FileChannel.open(
                            home.resolve(".managed-zig.lock"),
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE);
                    var ignored = channel.lock()) {
                return ensureLocked(home);
            }
        }
    }

    private ManagedZig ensureLocked(Path home) throws IOException {
        java.util.ArrayList<ManagedZigBootstrapEvent> events = new java.util.ArrayList<>();
        events.add(event("CHECKSUM_SIGNATURE_POLICY", "managed Zig verification policy: " + verifier.policy()));
        Path zigHome = home.resolve("zig");
        Path executable = zigHome.resolve(windows ? "zig.exe" : "zig");
        if (Files.exists(executable) && isExpectedVersion(executable)) {
            events.add(new ManagedZigBootstrapEvent(
                    "FOUND_MANAGED_ZIG",
                    "found managed Zig " + ZigArchiveResolver.ZIG_VERSION + " at " + executable,
                    null,
                    null,
                    "notApplicable",
                    "notApplicable",
                    "existingInstall"));
            return new ManagedZig(executable, zigHome, ZigArchiveResolver.ZIG_VERSION, verifier.policy(), events);
        }
        if (Files.exists(executable)) {
            events.add(event("WRONG_VERSION_REINSTALL", "managed Zig at " + executable
                    + " is missing or not version " + ZigArchiveResolver.ZIG_VERSION + "; reinstalling"));
        }
        ZigArchiveMetadata metadata;
        try {
            metadata = archiveResolver.currentHostArchive();
        } catch (RuntimeException exception) {
            throw new IOException("unsupported host archive metadata for managed Zig "
                    + ZigArchiveResolver.ZIG_VERSION + ": " + exception.getMessage(), exception);
        }
        Path archive = home.resolve(metadata.archiveName());
        String source;
        if (!Files.exists(archive)) {
            source = "download";
            events.add(archiveEvent(
                    "DOWNLOAD_ATTEMPTED",
                    "downloading managed Zig " + ZigArchiveResolver.ZIG_VERSION + " from " + metadata.downloadUri(),
                    metadata,
                    source,
                    "pending"));
            Path download = Files.createTempFile(home, metadata.archiveName() + ".", ".downloading");
            try {
                try {
                    downloader.download(metadata.downloadUri(), download);
                } catch (IOException exception) {
                    throw new IOException("failed to download managed Zig " + ZigArchiveResolver.ZIG_VERSION
                            + " from " + metadata.downloadUri(), exception);
                }
                verifier.verify(download, metadata);
                publish(download, archive);
            } finally {
                Files.deleteIfExists(download);
            }
        } else {
            source = "localArchive";
            events.add(archiveEvent(
                    "LOCAL_ARCHIVE_USED",
                    "using local managed Zig archive " + archive,
                    metadata,
                    source,
                    "pending"));
        }
        verifier.verify(archive, metadata);
        events.add(archiveEvent(
                "ARCHIVE_CHECKSUM_VERIFIED",
                "verified managed Zig archive SHA-256 for " + metadata.archiveName(),
                metadata,
                source,
                "verified"));
        extractor.extractNormalized(metadata, archive, zigHome);
        if (!Files.exists(executable)) {
            throw new IOException("managed Zig archive did not produce executable " + executable);
        }
        if (!isExpectedVersion(executable)) {
            throw new IOException("managed Zig version mismatch after install: expected "
                    + ZigArchiveResolver.ZIG_VERSION + " at " + executable);
        }
        events.add(event("INSTALLED_MANAGED_ZIG", "installed managed Zig "
                + ZigArchiveResolver.ZIG_VERSION + " at " + executable));
        return new ManagedZig(executable, zigHome, ZigArchiveResolver.ZIG_VERSION, verifier.policy(), events);
    }

    private ManagedZigBootstrapEvent event(String code, String message) {
        return new ManagedZigBootstrapEvent(code, message);
    }

    private void publish(Path source, Path destination) throws IOException {
        try {
            Files.move(
                    source,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private ManagedZigBootstrapEvent archiveEvent(
            String code,
            String message,
            ZigArchiveMetadata metadata,
            String source,
            String checksumStatus) {
        return new ManagedZigBootstrapEvent(
                code,
                message,
                metadata.archiveName(),
                metadata.expectedSha256(),
                checksumStatus,
                metadata.signatureAvailabilityPolicy(),
                source);
    }

    private boolean isExpectedVersion(Path executable) throws IOException {
        ZigCommandResult result = runner.run(
                List.of(executable.toString(), "version"),
                executable.getParent(),
                Map.of());
        return result.exitCode() == 0 && ZigArchiveResolver.ZIG_VERSION.equals(result.stdout().trim());
    }

    private static boolean isCurrentWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }
}
