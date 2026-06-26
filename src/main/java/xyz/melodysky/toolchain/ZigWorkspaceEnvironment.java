package xyz.melodysky.toolchain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class ZigWorkspaceEnvironment {
    private ZigWorkspaceEnvironment() {
    }

    public static Map<String, String> environment(Path workspaceRoot) throws IOException {
        Path cache = workspaceRoot.resolve("native").resolve("zig-cache");
        Path global = cache.resolve("global");
        Path local = cache.resolve("local");
        Path tmp = cache.resolve("tmp");
        Files.createDirectories(global);
        Files.createDirectories(local);
        Files.createDirectories(tmp);
        return Map.of(
                "ZIG_GLOBAL_CACHE_DIR", global.toAbsolutePath().toString(),
                "ZIG_LOCAL_CACHE_DIR", local.toAbsolutePath().toString(),
                "TMPDIR", tmp.toAbsolutePath().toString(),
                "TMP", tmp.toAbsolutePath().toString(),
                "TEMP", tmp.toAbsolutePath().toString());
    }
}
