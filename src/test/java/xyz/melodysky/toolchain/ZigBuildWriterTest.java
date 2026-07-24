package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZigBuildWriterTest {
    @TempDir
    Path temp;

    @Test
    void generatedBuildZigPlansSelectedTargetMatrixInOneWorkspace() throws Exception {
        ZigBuildWorkspace workspace = ZigBuildWorkspace.under(temp);
        ZigSourceSet sources = new ZigSourceSet(
                List.of(workspace.llvmDirectory().resolve("pkg_A.ll")),
                List.of(workspace.jniDirectory().resolve("wrapper.c"), workspace.runtimeDirectory().resolve("helper.c")),
                List.of(),
                List.of());
        NativeBuildPlan plan = new NativeBuildPlan(List.of(
                unit(TargetTriple.WINDOWS_X64),
                unit(TargetTriple.MACOS_X64),
                unit(TargetTriple.LINUX_ARM64)));

        new ZigBuildWriter().write(workspace, "j2lltest", plan, new ZigInputSet(sources));

        String expected = """
                const std = @import("std");

                pub fn build(b: *std.Build) void {
                    const optimize = .ReleaseSafe;
                    const progress_markers = b.addWriteFiles();

                    const target_linux_arm64 = b.resolveTargetQuery(.{ .cpu_arch = .aarch64, .os_tag = .linux, .os_version_min = .{ .semver = .{ .major = 3, .minor = 7, .patch = 0 } }, .abi = .gnu, .glibc_version = .{ .major = 2, .minor = 17, .patch = 0 } });
                    const module_linux_arm64 = b.createModule(.{
                        .target = target_linux_arm64,
                        .optimize = optimize,
                        .strip = true,
                        .link_libc = true,
                    });
                    module_linux_arm64.addCSourceFiles(.{
                        .root = b.path("."),
                        .files = &.{ "jni/wrapper.c", "runtime/helper.c" },
                        .language = .c,
                        .flags = &.{ "-g0", "-fvisibility=hidden", "-ffile-compilation-dir=.", "-fdebug-compilation-dir=." },
                    });
                    module_linux_arm64.addObjectFile(b.path("llvm/pkg_A.ll"));
                    const lib_linux_arm64 = b.addLibrary(.{
                        .linkage = .dynamic,
                        .name = "j2lltest",
                        .root_module = module_linux_arm64,
                    });
                    const install_linux_arm64 = b.addInstallArtifact(lib_linux_arm64, .{
                        .dest_dir = .{ .override = .prefix },
                        .dest_sub_path = "native/arm64-linux.so",
                    });
                    const marker_linux_arm64 = progress_markers.add("linux-arm64.done", "j2ll-target-complete-v1:linux-arm64\\n");
                    const install_marker_linux_arm64 = b.addInstallFileWithDir(marker_linux_arm64, .prefix, "logs/zig-progress/linux-arm64.done");
                    install_marker_linux_arm64.step.dependOn(&install_linux_arm64.step);
                    b.getInstallStep().dependOn(&install_marker_linux_arm64.step);

                    const target_macos_x64 = b.resolveTargetQuery(.{ .cpu_arch = .x86_64, .os_tag = .macos, .os_version_min = .{ .semver = .{ .major = 10, .minor = 15, .patch = 0 } } });
                    const module_macos_x64 = b.createModule(.{
                        .target = target_macos_x64,
                        .optimize = optimize,
                        .strip = true,
                        .link_libc = true,
                    });
                    module_macos_x64.addCSourceFiles(.{
                        .root = b.path("."),
                        .files = &.{ "jni/wrapper.c", "runtime/helper.c" },
                        .language = .c,
                        .flags = &.{ "-g0", "-fvisibility=hidden", "-ffile-compilation-dir=.", "-fdebug-compilation-dir=." },
                    });
                    module_macos_x64.addObjectFile(b.path("llvm/pkg_A.ll"));
                    const lib_macos_x64 = b.addLibrary(.{
                        .linkage = .dynamic,
                        .name = "j2lltest",
                        .root_module = module_macos_x64,
                    });
                    lib_macos_x64.discard_local_symbols = true;
                    const install_macos_x64 = b.addInstallArtifact(lib_macos_x64, .{
                        .dest_dir = .{ .override = .prefix },
                        .dest_sub_path = "native/x64-macos.dylib",
                    });
                    const marker_macos_x64 = progress_markers.add("macos-x64.done", "j2ll-target-complete-v1:macos-x64\\n");
                    const install_marker_macos_x64 = b.addInstallFileWithDir(marker_macos_x64, .prefix, "logs/zig-progress/macos-x64.done");
                    install_marker_macos_x64.step.dependOn(&install_macos_x64.step);
                    b.getInstallStep().dependOn(&install_marker_macos_x64.step);

                    const target_windows_x64 = b.resolveTargetQuery(.{ .cpu_arch = .x86_64, .os_tag = .windows, .abi = .gnu });
                    const module_windows_x64 = b.createModule(.{
                        .target = target_windows_x64,
                        .optimize = optimize,
                        .strip = true,
                        .link_libc = true,
                    });
                    module_windows_x64.addCSourceFiles(.{
                        .root = b.path("."),
                        .files = &.{ "jni/wrapper.c", "runtime/helper.c" },
                        .language = .c,
                        .flags = &.{ "-g0", "-fvisibility=hidden", "-ffile-compilation-dir=.", "-fdebug-compilation-dir=." },
                    });
                    module_windows_x64.addObjectFile(b.path("llvm/pkg_A.ll"));
                    const lib_windows_x64 = b.addLibrary(.{
                        .linkage = .dynamic,
                        .name = "j2lltest",
                        .root_module = module_windows_x64,
                    });
                    const install_windows_x64 = b.addInstallArtifact(lib_windows_x64, .{
                        .dest_dir = .{ .override = .prefix },
                        .implib_dir = .disabled,
                        .dest_sub_path = "native/x64-windows.dll",
                    });
                    const marker_windows_x64 = progress_markers.add("windows-x64.done", "j2ll-target-complete-v1:windows-x64\\n");
                    const install_marker_windows_x64 = b.addInstallFileWithDir(marker_windows_x64, .prefix, "logs/zig-progress/windows-x64.done");
                    install_marker_windows_x64.step.dependOn(&install_windows_x64.step);
                    b.getInstallStep().dependOn(&install_marker_windows_x64.step);
                }
                """;
        assertEquals(expected, Files.readString(workspace.buildZig()));

        String manifest = Files.readString(workspace.manifest());
        assertTrue(manifest.contains("\"libraryName\": \"j2lltest\""));
        assertTrue(manifest.contains("\"llvm/pkg_A.ll\""));
        assertTrue(manifest.contains("\"target\": \"linux-arm64\""));
        assertTrue(manifest.contains("\"target\": \"macos-x64\""));
        assertTrue(manifest.contains("\"target\": \"windows-x64\""));
    }

    @Test
    void manifestRecordsSelectedTargetsThatArePreflightOnly() throws Exception {
        ZigBuildWorkspace workspace = ZigBuildWorkspace.under(temp);
        ZigSourceSet sources = new ZigSourceSet(
                List.of(),
                List.of(workspace.jniDirectory().resolve("wrapper.c")),
                List.of(),
                List.of());
        NativeBuildUnit macos = unit(TargetTriple.MACOS_ARM64);
        NativeBuildTargetPreflight linux = new NativeBuildTargetPreflight(
                TargetTriple.LINUX_X64,
                temp.resolve("native/x64-linux.so"),
                "j2lltest",
                false,
                false,
                "ZIG_TARGET_UNBUILDABLE",
                "synthetic unsupported target for manifest coverage",
                "managedZig0.15.2CrossTargetSharedLibrary",
                "synthetic missing capability",
                true,
                "syntheticFailure",
                "synthetic preflight failure");
        NativeBuildTargetPreflight macosPreflight = new NativeBuildTargetPreflight(
                macos.target(),
                macos.outputPath(),
                macos.libraryName(),
                true,
                true,
                "CURRENT_HOST_TARGET",
                "synthetic host target",
                "managedZig0.15.2CrossTargetSharedLibrary",
                "managed Zig target support");
        NativeBuildPlan plan = new NativeBuildPlan(List.of(macos), List.of(linux, macosPreflight));

        new ZigBuildWriter().write(workspace, "j2lltest", plan, new ZigInputSet(sources));

        String buildZig = Files.readString(workspace.buildZig());
        assertTrue(buildZig.contains("const target_macos_arm64"));
        assertTrue(!buildZig.contains("const target_linux_x64"));
        String manifest = Files.readString(workspace.manifest());
        assertTrue(manifest.contains("\"selectedTargets\""));
        assertTrue(manifest.contains("\"requiredTargets\""));
        assertTrue(manifest.contains("\"linux-x64\""));
        assertTrue(manifest.contains("\"buildableTargets\""));
        assertTrue(manifest.contains("\"skippedTargets\""));
        assertTrue(manifest.contains("\"failedTargets\""));
        assertTrue(manifest.contains("\"status\": \"failed\""));
        assertTrue(manifest.contains("\"reasonCode\": \"ZIG_TARGET_UNBUILDABLE\""));
        assertTrue(manifest.contains("\"requiredCapability\": \"managedZig0.15.2CrossTargetSharedLibrary\""));
    }

    @Test
    void resolvedBinaryStripPolicyControlsZigModule() {
        ZigBuildWorkspace workspace = ZigBuildWorkspace.under(temp);
        NativeBuildPlan plan = new NativeBuildPlan(List.of(unit(TargetTriple.LINUX_X64)));

        String buildZig = new ZigBuildWriter().buildZig(
                workspace,
                "j2lltest",
                plan,
                new ZigSourceSet(List.of(), List.of(), List.of(), List.of()),
                false);

        assertTrue(buildZig.contains(".strip = false"));
    }

    @Test
    void rejectsLibraryNameThatCouldInjectAPathOrZigSource() {
        ZigBuildWorkspace workspace = ZigBuildWorkspace.under(temp);
        NativeBuildPlan plan = new NativeBuildPlan(List.of(unit(TargetTriple.LINUX_X64)));

        assertThrows(
                IllegalArgumentException.class,
                () -> new ZigBuildWriter().buildZig(
                        workspace,
                        "../outside\nconst injected = true",
                        plan,
                        new ZigSourceSet(List.of(), List.of(), List.of(), List.of()),
                        true));
    }

    private NativeBuildUnit unit(TargetTriple target) {
        return new NativeBuildUnit(
                target,
                temp.resolve("native").resolve(target.libraryFileName()),
                "j2lltest");
    }
}
