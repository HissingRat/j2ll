package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
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

                    const target_linux_arm64 = b.resolveTargetQuery(.{ .cpu_arch = .aarch64, .os_tag = .linux });
                    const module_linux_arm64 = b.createModule(.{
                        .target = target_linux_arm64,
                        .optimize = optimize,
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
                        .dest_sub_path = "native/linux-arm64/arm64-linux.so",
                    });
                    b.getInstallStep().dependOn(&install_linux_arm64.step);

                    const target_macos_x64 = b.resolveTargetQuery(.{ .cpu_arch = .x86_64, .os_tag = .macos });
                    const module_macos_x64 = b.createModule(.{
                        .target = target_macos_x64,
                        .optimize = optimize,
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
                        .dest_sub_path = "native/macos-x64/x64-macos.dylib",
                    });
                    b.getInstallStep().dependOn(&install_macos_x64.step);

                    const target_windows_x64 = b.resolveTargetQuery(.{ .cpu_arch = .x86_64, .os_tag = .windows });
                    const module_windows_x64 = b.createModule(.{
                        .target = target_windows_x64,
                        .optimize = optimize,
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
                        .dest_sub_path = "native/windows-x64/x64-windows.dll",
                    });
                    b.getInstallStep().dependOn(&install_windows_x64.step);
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
        NativeBuildPlan plan = new NativeBuildPlanner(Optional.of(new HostPlatform(TargetTriple.MACOS_ARM64, "darwin"))).plan(
                temp,
                "j2lltest",
                List.of(TargetTriple.LINUX_X64, TargetTriple.MACOS_ARM64));

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
        assertTrue(manifest.contains("\"requiredCapability\": \"managedZig0.15.2BuildZigSharedLibrary\""));
    }

    private NativeBuildUnit unit(TargetTriple target) {
        return new NativeBuildUnit(
                target,
                temp.resolve("native").resolve(target.directoryName()).resolve(target.libraryFileName()),
                "j2lltest");
    }
}
