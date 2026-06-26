package xyz.melodysky.toolchain;

import java.nio.file.Path;
import java.util.Objects;

public record ZigBuildWorkspace(
        Path workspaceRoot,
        Path buildDirectory,
        Path buildZig,
        Path manifest,
        Path llvmDirectory,
        Path jniDirectory,
        Path runtimeDirectory,
        Path fallbackDirectory,
        Path logsDirectory) {
    public ZigBuildWorkspace {
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        Objects.requireNonNull(buildDirectory, "buildDirectory");
        Objects.requireNonNull(buildZig, "buildZig");
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(llvmDirectory, "llvmDirectory");
        Objects.requireNonNull(jniDirectory, "jniDirectory");
        Objects.requireNonNull(runtimeDirectory, "runtimeDirectory");
        Objects.requireNonNull(fallbackDirectory, "fallbackDirectory");
        Objects.requireNonNull(logsDirectory, "logsDirectory");
    }

    public static ZigBuildWorkspace under(Path workspaceRoot) {
        Path build = workspaceRoot.resolve("native").resolve("zig-workspace");
        return new ZigBuildWorkspace(
                workspaceRoot,
                build,
                build.resolve("build.zig"),
                build.resolve("j2ll-build-manifest.json"),
                build.resolve("llvm"),
                build.resolve("jni"),
                build.resolve("runtime"),
                build.resolve("fallback"),
                workspaceRoot.resolve("logs"));
    }
}
