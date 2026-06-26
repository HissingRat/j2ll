package xyz.melodysky.toolchain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class ManagedZigLocator {
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
                ZigArchiveVerifier.boundaryOnly(),
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
        Path zigHome = home.resolve("zig");
        Path executable = zigHome.resolve(windows ? "zig.exe" : "zig");
        if (Files.exists(executable) && isExpectedVersion(executable)) {
            return new ManagedZig(executable, zigHome, ZigArchiveResolver.ZIG_VERSION, verifier.policy());
        }
        ZigArchiveMetadata metadata = archiveResolver.currentHostArchive();
        Path archive = home.resolve(metadata.archiveName());
        if (!Files.exists(archive)) {
            try {
                downloader.download(metadata.downloadUri(), archive);
            } catch (IOException exception) {
                throw new IOException("failed to download managed Zig " + ZigArchiveResolver.ZIG_VERSION
                        + " from " + metadata.downloadUri(), exception);
            }
        }
        verifier.verify(archive, metadata);
        extractor.extractNormalized(metadata, archive, zigHome);
        if (!Files.exists(executable)) {
            throw new IOException("managed Zig archive did not produce executable " + executable);
        }
        if (!isExpectedVersion(executable)) {
            throw new IOException("managed Zig version mismatch after install: expected "
                    + ZigArchiveResolver.ZIG_VERSION + " at " + executable);
        }
        return new ManagedZig(executable, zigHome, ZigArchiveResolver.ZIG_VERSION, verifier.policy());
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
