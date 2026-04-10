package xyz.melodysky.zig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ZigWorkspaceEnvironment {

    private ZigWorkspaceEnvironment() {
    }

    public static void configure(Map<String, String> environment, List<String> command,
                                 Path workspaceDirectory, boolean windows) throws IOException {
        if (!isZigCommand(command)) {
            return;
        }
        Path zigCacheRoot = cacheRoot(workspaceDirectory);
        Path zigGlobalCache = zigCacheRoot.resolve("global");
        Path zigLocalCache = zigCacheRoot.resolve("local");
        Path zigTemp = zigCacheRoot.resolve("tmp");
        Files.createDirectories(zigGlobalCache);
        Files.createDirectories(zigLocalCache);
        Files.createDirectories(zigTemp);
        environment.put("ZIG_GLOBAL_CACHE_DIR", zigGlobalCache.toAbsolutePath().toString());
        environment.put("ZIG_LOCAL_CACHE_DIR", zigLocalCache.toAbsolutePath().toString());
        environment.put("TMPDIR", zigTemp.toAbsolutePath().toString());
        if (windows) {
            environment.put("TEMP", zigTemp.toAbsolutePath().toString());
            environment.put("TMP", zigTemp.toAbsolutePath().toString());
        }
    }

    public static Path cacheRoot(Path workspaceDirectory) {
        return workspaceDirectory.resolve("zig-cache");
    }

    public static boolean isZigCommand(List<String> command) {
        if (command == null || command.isEmpty()) {
            return false;
        }
        String executable = command.getFirst();
        String fileName = Path.of(executable).getFileName().toString().toLowerCase(Locale.ROOT);
        return "zig".equals(fileName) || "zig.exe".equals(fileName);
    }
}
