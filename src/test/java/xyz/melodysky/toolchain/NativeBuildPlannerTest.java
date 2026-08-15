package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeBuildPlannerTest {
    @Test
    void plansHostAndCrossTargetsAsBuildableUnits() {
        NativeBuildPlan plan = new NativeBuildPlanner(Optional.of(new HostPlatform(TargetTriple.MACOS_ARM64, "darwin"))).plan(
                Path.of("/work"),
                "j2llapp",
                List.of(TargetTriple.LINUX_X64, TargetTriple.MACOS_ARM64));

        assertEquals(2, plan.units().size());
        assertEquals(Path.of("/work/native/x64-linux.so"), plan.units().get(0).outputPath());
        assertEquals(Path.of("/work/native/arm64-macos.dylib"), plan.units().get(1).outputPath());
        assertEquals(2, plan.targetPreflights().size());
        assertEquals(List.of("linux-x64", "macos-arm64"), plan.targetPreflights().stream()
                .map(preflight -> preflight.target().directoryName())
                .toList());
        assertTrue(plan.targetPreflights().get(0).buildable());
        assertEquals("buildable", plan.targetPreflights().get(0).status());
        assertEquals("ZIG_CROSS_TARGET_SUPPORTED", plan.targetPreflights().get(0).reasonCode());
        assertTrue(plan.targetPreflights().get(0).required());
        assertEquals("none", plan.targetPreflights().get(0).failureKind());
        assertTrue(plan.targetPreflights().get(0).buildLogTail().contains("matrix-wide Zig build"));
        assertTrue(plan.targetPreflights().get(1).buildable());
        assertEquals("CURRENT_HOST_TARGET", plan.targetPreflights().get(1).reasonCode());
        assertEquals("none", plan.targetPreflights().get(1).failureKind());
    }

    @Test
    void doesNotRequireARecognizedHostToPlanCrossTargets() {
        NativeBuildPlan plan = new NativeBuildPlanner(Optional.empty()).plan(
                Path.of("/work"),
                "j2llapp",
                List.of(TargetTriple.LINUX_X64));

        assertEquals(1, plan.units().size());
        assertTrue(plan.skippedTargetPreflights().isEmpty());
        assertTrue(plan.failedTargetPreflights().isEmpty());
        assertEquals("ZIG_CROSS_TARGET_SUPPORTED", plan.targetPreflights().get(0).reasonCode());
        assertEquals("none", plan.targetPreflights().get(0).failureKind());
        assertEquals(Path.of("/work/native/x64-linux.so"),
                plan.targetPreflights().get(0).outputPath());
    }

    @Test
    void plansCrossPlatformPackagePathsInDeterministicClassifierOrder() {
        NativeBuildPlan plan = new NativeBuildPlanner(Optional.of(new HostPlatform(TargetTriple.MACOS_ARM64, "darwin"))).plan(
                Path.of("/work"),
                "j2llapp",
                List.of(
                        TargetTriple.MACOS_ARM64,
                        TargetTriple.WINDOWS_X64,
                        TargetTriple.LINUX_X64,
                        TargetTriple.WINDOWS_ARM64,
                        TargetTriple.MACOS_X64,
                        TargetTriple.LINUX_ARM64));

        assertEquals(List.of(
                "linux-arm64",
                "linux-x64",
                "macos-arm64",
                "macos-x64",
                "windows-arm64",
                "windows-x64"), plan.targetPreflights().stream()
                .map(preflight -> preflight.target().directoryName())
                .toList());
        assertTarget(plan.targetPreflights().get(0), TargetTriple.LINUX_ARM64, "linux", "arm64", "so",
                Path.of("/work/native/arm64-linux.so"), "none");
        assertTarget(plan.targetPreflights().get(1), TargetTriple.LINUX_X64, "linux", "x64", "so",
                Path.of("/work/native/x64-linux.so"), "none");
        assertTarget(plan.targetPreflights().get(2), TargetTriple.MACOS_ARM64, "macos", "arm64", "dylib",
                Path.of("/work/native/arm64-macos.dylib"), "none");
        assertTarget(plan.targetPreflights().get(3), TargetTriple.MACOS_X64, "macos", "x64", "dylib",
                Path.of("/work/native/x64-macos.dylib"), "none");
        assertTarget(plan.targetPreflights().get(4), TargetTriple.WINDOWS_ARM64, "windows", "arm64", "dll",
                Path.of("/work/native/arm64-windows.dll"), "none");
        assertTarget(plan.targetPreflights().get(5), TargetTriple.WINDOWS_X64, "windows", "x64", "dll",
                Path.of("/work/native/x64-windows.dll"), "none");
        assertTrue(plan.targetPreflights().stream().allMatch(NativeBuildTargetPreflight::buildable));
        assertTrue(plan.failedTargetPreflights().isEmpty());
    }

    @Test
    void pinsCrossTargetAbiAndMinimumRuntimeVersions() {
        assertEquals("x86_64-windows-gnu", TargetTriple.WINDOWS_X64.zigTarget());
        assertEquals("aarch64-linux.3.7-gnu.2.17", TargetTriple.LINUX_ARM64.zigTarget());
        assertEquals("x86_64-macos.10.15", TargetTriple.MACOS_X64.zigTarget());
        assertEquals("aarch64-macos.11.0", TargetTriple.MACOS_ARM64.zigTarget());
        assertTrue(TargetTriple.LINUX_X64.zigTargetQuery().contains(".glibc_version"));
        assertTrue(TargetTriple.LINUX_X64.zigTargetQuery().contains(".major = 3, .minor = 2"));
        assertTrue(TargetTriple.LINUX_ARM64.zigTargetQuery().contains(".major = 3, .minor = 7"));
        assertTrue(TargetTriple.WINDOWS_ARM64.zigTargetQuery().contains(".abi = .gnu"));
        assertTrue(TargetTriple.MACOS_X64.zigTargetQuery().contains(".major = 10, .minor = 15"));
    }

    @Test
    void flatNativeLibraryNamesAreUniqueIgnoringCase() {
        List<String> names = java.util.Arrays.stream(TargetTriple.values())
                .map(TargetTriple::libraryFileName)
                .map(name -> name.toLowerCase(java.util.Locale.ROOT))
                .toList();

        assertEquals((long) names.size(), names.stream().distinct().count());
        assertTrue(names.stream().noneMatch(name -> name.contains("/") || name.contains("\\")));
    }

    @Test
    void derivesDeterministicInternalLibraryNameFromProtectionSeed() {
        String first = NativeLibraryName.derive("seed-a");
        String repeated = NativeLibraryName.derive("seed-a");
        String different = NativeLibraryName.derive("seed-b");

        assertEquals(first, repeated);
        assertTrue(NativeLibraryName.isSafe(first));
        assertTrue(first.matches("[0-9a-f]{16}"));
        assertTrue(!first.contains("j2ll"));
        assertTrue(!first.equals(different));
        assertTrue(different.startsWith("0"), different);
        assertTrue(NativeLibraryName.isSafe(different));
    }

    @Test
    void marksTargetsUnbuildableWhenCurrentRuntimeHasNoJniHeaders(@TempDir Path temp) {
        NativeBuildPlan plan = new NativeBuildPlanner(
                        Optional.empty(),
                        new ManagedZigTargetCapabilities(temp.resolve("missing-jni.h")))
                .plan(temp, "j2llapp", List.of(TargetTriple.LINUX_X64));

        assertTrue(plan.units().isEmpty());
        assertEquals("JNI_HEADERS_UNAVAILABLE", plan.failedTargetPreflights().get(0).reasonCode());
        assertEquals("missingJniHeaders", plan.failedTargetPreflights().get(0).failureKind());
    }

    @Test
    void postBuildFailureMarksOnlyMissingRequiredTargetsFailed(@TempDir Path workspace) throws Exception {
        NativeBuildPlan plan = new NativeBuildPlanner(Optional.of(
                new HostPlatform(TargetTriple.WINDOWS_X64, "win32"))).plan(
                workspace,
                "j2llapp",
                List.of(TargetTriple.LINUX_X64, TargetTriple.WINDOWS_X64));
        NativeBuildUnit windows = plan.units().stream()
                .filter(unit -> unit.target() == TargetTriple.WINDOWS_X64)
                .findFirst()
                .orElseThrow();
        Files.createDirectories(windows.outputPath().getParent());
        Files.write(windows.outputPath(), new byte[] {1});
        ZigBuildWorkspace zigWorkspace = ZigBuildWorkspace.under(workspace);
        Files.createDirectories(zigWorkspace.logsDirectory());
        Files.writeString(zigWorkspace.logsDirectory().resolve("zig-build.log"), "linux link failed");

        ZigBuildException failure = ZigBuildException.from(plan, zigWorkspace, new IOException("failed"));
        NativeBuildPlan failedPlan = plan.withBuildFailures(
                failure.failedTargets(),
                "zigBuildFailed",
                failure.logTail());

        assertEquals(List.of(TargetTriple.LINUX_X64), failure.failedTargets());
        assertEquals(List.of(TargetTriple.WINDOWS_X64), failedPlan.units().stream()
                .map(NativeBuildUnit::target)
                .toList());
        assertEquals("ZIG_TARGET_UNBUILDABLE", failedPlan.failedTargetPreflights().get(0).reasonCode());
        assertEquals("zigBuildFailed", failedPlan.failedTargetPreflights().get(0).failureKind());
        assertTrue(failedPlan.failedTargetPreflights().get(0).buildLogTail().contains("linux link failed"));
    }

    private void assertTarget(
            NativeBuildTargetPreflight preflight,
            TargetTriple target,
            String os,
            String arch,
            String extension,
            Path output,
            String failureKind) {
        assertEquals(target, preflight.target());
        assertEquals(os, target.osClassifier());
        assertEquals(arch, target.archClassifier());
        assertEquals(extension, target.libraryExtension());
        assertEquals(output, preflight.outputPath());
        assertEquals(failureKind, preflight.failureKind());
        assertTrue(preflight.required());
        assertTrue(preflight.buildLogTail().contains("matrix-wide Zig build"));
    }
}
