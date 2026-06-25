package xyz.melodysky.toolchain;

import xyz.melodysky.config.BuildTarget;
import xyz.melodysky.zig.ZigWorkspaceEnvironment;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

final class NativeBuildWorkspacePaths {
    private final Path workspaceDirectory;

    NativeBuildWorkspacePaths(Path workspaceDirectory) {
        this.workspaceDirectory = workspaceDirectory;
    }

    Path workspaceDirectory() {
        return workspaceDirectory;
    }

    Path outputDirectory() {
        return workspaceDirectory.resolve("native");
    }

    Path logsDirectory() {
        return workspaceDirectory.resolve("logs");
    }

    Path buildProjectDirectory() {
        return workspaceDirectory.resolve("zig-build");
    }

    Path objectDirectory(BuildTarget target) {
        return workspaceDirectory.resolve("native-obj").resolve(target.getConfigKey());
    }

    Path libraryFile(BuildTarget target) {
        return outputDirectory().resolve(outputFileName(target));
    }

    Path logFile(BuildTarget target) {
        return logsDirectory().resolve("zig-build-" + target.getConfigKey() + ".log");
    }

    Path zigBuildCacheDirectory(Path buildProjectDirectory) {
        return buildProjectDirectory.toAbsolutePath().normalize().resolve(".zig-cache");
    }

    Path zigGlobalCacheDirectory() {
        return ZigWorkspaceEnvironment.cacheRoot(workspaceDirectory).resolve("global").toAbsolutePath().normalize();
    }

    Path ensureBundledJniHeaders(BuildTarget target) {
        Path includeDirectory = workspaceDirectory.resolve("jni-headers");
        writeResource("/jni-headers/jni.h", includeDirectory.resolve("jni.h"));
        writeResource(
                "/jni-headers/" + target.getJniHeaderSubdir() + "/jni_md.h",
                includeDirectory.resolve(target.getJniHeaderSubdir()).resolve("jni_md.h")
        );
        return includeDirectory;
    }

    String outputFileName(BuildTarget target) {
        String arch = switch (target) {
            case WINDOWS_X64, LINUX_X64, MACOS_X64 -> "x64";
            case WINDOWS_ARM64, LINUX_ARM64, MACOS_ARM64 -> "arm64";
        };

        String suffix = switch (target) {
            case WINDOWS_X64, WINDOWS_ARM64 -> "windows.dll";
            case LINUX_X64, LINUX_ARM64 -> "linux.so";
            case MACOS_X64, MACOS_ARM64 -> "macos.dylib";
        };

        return arch + "-" + suffix;
    }

    String moduleObjectName(int index, Path llvmModuleFile, BuildTarget target) {
        return String.format("%02d-%s%s", index, baseName(llvmModuleFile.getFileName().toString()), objectFileExtension(target));
    }

    String runtimeObjectName(int index, Path runtimeSourceFile, BuildTarget target) {
        return String.format("runtime-%02d-%s%s", index, baseName(runtimeSourceFile.getFileName().toString()), objectFileExtension(target));
    }

    List<String> createPathSanitizingFlags() {
        String workspacePath = workspaceDirectory.toAbsolutePath().normalize().toString().replace('\\', '/');
        return List.of(
                "-ffile-prefix-map=" + workspacePath + "=.",
                "-fdebug-prefix-map=" + workspacePath + "=.",
                "-fmacro-prefix-map=" + workspacePath + "=."
        );
    }

    String commandPath(Path path) {
        Path absolutePath = path.toAbsolutePath().normalize();
        Path workspacePath = workspaceDirectory.toAbsolutePath().normalize();
        try {
            if (absolutePath.startsWith(workspacePath)) {
                return workspacePath.relativize(absolutePath).toString().replace('\\', '/');
            }
        } catch (Exception ignored) {
        }
        return absolutePath.toString();
    }

    String relativeTo(Path root, Path child) {
        return root.toAbsolutePath().normalize().relativize(child.toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
    }

    void cleanupIntermediates() {
        deleteWorkspacePathQuietly(ZigWorkspaceEnvironment.cacheRoot(workspaceDirectory));
        deleteWorkspacePathQuietly(workspaceDirectory.resolve("native-obj"));
        deleteWorkspacePathQuietly(buildProjectDirectory());
    }

    private String baseName(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex >= 0 ? fileName.substring(0, dotIndex) : fileName;
    }

    private String objectFileExtension(BuildTarget target) {
        return switch (target) {
            case WINDOWS_X64, WINDOWS_ARM64 -> ".obj";
            case LINUX_X64, LINUX_ARM64, MACOS_X64, MACOS_ARM64 -> ".o";
        };
    }

    private void writeResource(String resourcePath, Path outputPath) {
        try {
            Files.createDirectories(outputPath.toAbsolutePath().getParent());
            try (InputStream input = NativeBuildWorkspacePaths.class.getResourceAsStream(resourcePath)) {
                if (input == null) {
                    throw new IllegalStateException("Missing bundled resource: " + resourcePath);
                }
                Files.write(outputPath, input.readAllBytes());
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to materialize bundled resource: " + resourcePath, exception);
        }
    }

    private void deleteWorkspacePathQuietly(Path rootDirectory) {
        if (rootDirectory == null || Files.notExists(rootDirectory)) {
            return;
        }
        try (var stream = Files.walk(rootDirectory)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
        }
    }
}
