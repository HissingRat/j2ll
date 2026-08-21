package xyz.melodysky.testsupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import xyz.melodysky.toolchain.J2llHomeResolver;
import xyz.melodysky.toolchain.ManagedZigLocator;
import xyz.melodysky.toolchain.ZigArchiveExtractor;
import xyz.melodysky.toolchain.ZigArchiveMetadata;
import xyz.melodysky.toolchain.ZigArchiveResolver;
import xyz.melodysky.toolchain.ZigArchiveVerifier;
import xyz.melodysky.toolchain.ZigCommandRunner;
import xyz.melodysky.toolchain.ZigDownloader;

/** Supplies the real managed Zig used only by the default Dummy E2E tests. */
public final class DummyManagedZigTestSupport {
    private static final String CACHE_ROOT_PROPERTY = "j2ll.testZigCacheRoot";
    private static final String GRADLE_OFFLINE_PROPERTY = "j2ll.gradleOffline";

    private DummyManagedZigTestSupport() {}

    public static AutoCloseable use() throws IOException {
        return use(
                explicitJ2llHome(),
                cacheRoot(),
                Boolean.parseBoolean(System.getProperty(GRADLE_OFFLINE_PROPERTY, "false")));
    }

    static AutoCloseable use(Path explicitHome, Path cacheRoot, boolean offline) throws IOException {
        if (explicitHome != null) {
            Path executable = zigExecutable(explicitHome);
            if (!Files.isRegularFile(executable)) {
                throw new IOException("configured real j2ll home has no managed Zig executable: " + executable);
            }
            return useJ2llHome(explicitHome);
        }

        ZigArchiveResolver resolver = new ZigArchiveResolver();
        ZigArchiveMetadata archive = resolver.currentHostArchive();
        Path cacheHome = cacheRoot
                .resolve(ZigArchiveResolver.ZIG_VERSION)
                .resolve(archive.archiveName())
                .toAbsolutePath()
                .normalize();
        locator(resolver, offline).ensure(cacheHome);
        return useJ2llHome(cacheHome);
    }

    private static ManagedZigLocator locator(ZigArchiveResolver resolver, boolean offline) {
        ZigDownloader downloader = offline
                ? (uri, destination) -> {
                    throw new IOException("Gradle offline mode cannot download managed Zig "
                            + ZigArchiveResolver.ZIG_VERSION + "; populate the test cache first: " + destination);
                }
                : ZigDownloader.http();
        return new ManagedZigLocator(
                resolver,
                downloader,
                new ZigArchiveExtractor(),
                ZigArchiveVerifier.sha256(),
                ZigCommandRunner.process(),
                isWindows());
    }

    private static Path explicitJ2llHome() {
        String configured = System.getProperty("j2ll.realHome");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("J2LL_REAL_HOME");
        }
        return configured == null || configured.isBlank()
                ? null
                : Path.of(configured).toAbsolutePath().normalize();
    }

    private static Path cacheRoot() {
        String configured = System.getProperty(CACHE_ROOT_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        String gradleHome = System.getenv("GRADLE_USER_HOME");
        Path home = gradleHome == null || gradleHome.isBlank()
                ? Path.of(System.getProperty("user.home"), ".gradle")
                : Path.of(gradleHome);
        return home.resolve("caches/j2ll/zig");
    }

    private static Path zigExecutable(Path home) {
        return home.resolve("zig").resolve(isWindows() ? "zig.exe" : "zig");
    }

    private static AutoCloseable useJ2llHome(Path home) {
        String previous = System.getProperty(J2llHomeResolver.OVERRIDE_PROPERTY);
        System.setProperty(J2llHomeResolver.OVERRIDE_PROPERTY, home.toString());
        return () -> {
            if (previous == null) {
                System.clearProperty(J2llHomeResolver.OVERRIDE_PROPERTY);
            } else {
                System.setProperty(J2llHomeResolver.OVERRIDE_PROPERTY, previous);
            }
        };
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
