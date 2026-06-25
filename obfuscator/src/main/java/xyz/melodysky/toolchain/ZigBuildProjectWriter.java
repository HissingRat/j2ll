package xyz.melodysky.toolchain;

import xyz.melodysky.config.BuildTarget;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class ZigBuildProjectWriter {
    private final NativeBuildWorkspacePaths paths;

    ZigBuildProjectWriter(NativeBuildWorkspacePaths paths) {
        this.paths = paths;
    }

    Path prepare(Path outputDirectory, List<NativeTargetBuildState> targetStates,
                 List<Path> runtimeSourceFiles) throws IOException {
        Path buildProjectDirectory = paths.buildProjectDirectory();
        Files.createDirectories(buildProjectDirectory);
        Path buildFile = buildProjectDirectory.resolve("build.zig");
        Files.writeString(
                buildFile,
                createZigBuildFileText(outputDirectory, buildProjectDirectory, targetStates, runtimeSourceFiles),
                StandardCharsets.UTF_8
        );
        return buildProjectDirectory;
    }

    String createZigBuildFileText(Path outputDirectory, Path buildProjectDirectory,
                                  List<NativeTargetBuildState> targetStates, List<Path> runtimeSourceFiles) {
        String runtimeFiles = runtimeSourceFiles.stream()
                .map(path -> quoteZigString(path.getFileName().toString()))
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        String pathFlags = paths.createPathSanitizingFlags().stream()
                .map(this::quoteZigString)
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        String targetBlocks = targetStates.stream()
                .map(targetState -> createZigTargetBlock(targetState, buildProjectDirectory, runtimeFiles, pathFlags))
                .reduce((left, right) -> left + System.lineSeparator() + System.lineSeparator() + right)
                .orElse("");
        return """
                const std = @import("std");

                pub fn build(b: *std.Build) void {
                %s
                }
                """.formatted(indentBlock(targetBlocks, 4));
    }

    private String createZigTargetBlock(NativeTargetBuildState targetState, Path buildProjectDirectory,
                                        String runtimeFiles, String pathFlags) {
        String symbol = targetState.target().getConfigKey();
        Path jniHeadersDirectory = paths.ensureBundledJniHeaders(targetState.target());
        String includeDir = quoteZigString(paths.relativeTo(buildProjectDirectory, jniHeadersDirectory));
        String includePlatformDir = quoteZigString(paths.relativeTo(buildProjectDirectory, jniHeadersDirectory.resolve(targetState.target().getJniHeaderSubdir())));
        String outputName = quoteZigString(targetState.libraryFile().getFileName().toString());
        String arch = quoteZigEnum(zigCpuArch(targetState.target()));
        String os = quoteZigEnum(zigOsTag(targetState.target()));
        String objectFileLines = targetState.compileUnits().stream()
                .map(NativeCompileUnit::objectFile)
                .map(path -> "mod_" + symbol + ".addObjectFile(b.path(" + quoteZigString(paths.relativeTo(buildProjectDirectory, path)) + "));")
                .reduce((left, right) -> left + System.lineSeparator() + right)
                .orElse("");
        String macosDiscardLine = targetState.target().name().startsWith("MACOS")
                ? "lib_" + symbol + ".discard_local_symbols = true;" + System.lineSeparator()
                : "";
        String implibDirLine = targetState.target().name().startsWith("WINDOWS")
                ? "    .implib_dir = .disabled," + System.lineSeparator()
                : "";
        return """
                const target_%s = b.resolveTargetQuery(.{ .cpu_arch = %s, .os_tag = %s });
                const mod_%s = b.createModule(.{
                    .target = target_%s,
                    .optimize = .ReleaseSafe,
                    .strip = true,
                    .link_libc = true,
                });
                const lib_%s = b.addLibrary(.{
                    .linkage = .dynamic,
                    .name = %s,
                    .root_module = mod_%s,
                });
                %s
                mod_%s.addIncludePath(b.path(%s));
                mod_%s.addIncludePath(b.path(%s));
                mod_%s.addCSourceFiles(.{
                    .root = b.path(%s),
                    .files = &.{ %s },
                    .language = .c,
                    .flags = &.{ "-g0", "-ffile-compilation-dir=.", "-fdebug-compilation-dir=.", %s },
                });
                %s
                const artifact_%s = b.addInstallArtifact(lib_%s, .{
                    .dest_dir = .{ .override = .prefix },
                %s
                    .dest_sub_path = %s,
                });
                const step_%s = b.step(%s, %s);
                step_%s.dependOn(&artifact_%s.step);
                """.formatted(
                symbol,
                arch,
                os,
                symbol,
                symbol,
                symbol,
                quoteZigString("irnative_" + symbol),
                symbol,
                indentBlock(macosDiscardLine, 0),
                symbol,
                includeDir,
                symbol,
                includePlatformDir,
                symbol,
                quoteZigString(paths.relativeTo(buildProjectDirectory, paths.workspaceDirectory().resolve("runtime"))),
                runtimeFiles,
                pathFlags,
                objectFileLines.isBlank() ? "" : indentBlock(objectFileLines, 0) + System.lineSeparator(),
                symbol,
                symbol,
                indentBlock(implibDirLine, 0),
                outputName,
                symbol,
                quoteZigString(symbol),
                quoteZigString("Build " + symbol + " native library"),
                symbol,
                symbol
        );
    }

    private String indentBlock(String text, int spaces) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String indent = " ".repeat(Math.max(0, spaces));
        return text.lines()
                .map(line -> line.isEmpty() ? line : indent + line)
                .reduce((left, right) -> left + System.lineSeparator() + right)
                .orElse("");
    }

    private String quoteZigString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String quoteZigEnum(String value) {
        return "." + value;
    }

    private String zigCpuArch(BuildTarget target) {
        return switch (target) {
            case WINDOWS_X64, LINUX_X64, MACOS_X64 -> "x86_64";
            case WINDOWS_ARM64, LINUX_ARM64, MACOS_ARM64 -> "aarch64";
        };
    }

    private String zigOsTag(BuildTarget target) {
        return switch (target) {
            case WINDOWS_X64, WINDOWS_ARM64 -> "windows";
            case LINUX_X64, LINUX_ARM64 -> "linux";
            case MACOS_X64, MACOS_ARM64 -> "macos";
        };
    }
}
