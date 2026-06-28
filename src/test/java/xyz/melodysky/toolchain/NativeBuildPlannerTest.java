package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class NativeBuildPlannerTest {
    @Test
    void plansHostTargetLibraryPathAsBuildableUnit() {
        NativeBuildPlan plan = new NativeBuildPlanner(Optional.of(new HostPlatform(TargetTriple.MACOS_ARM64, "darwin"))).plan(
                Path.of("/work"),
                "j2llapp",
                List.of(TargetTriple.LINUX_X64, TargetTriple.MACOS_ARM64));

        assertEquals(1, plan.units().size());
        assertEquals(Path.of("/work/native/macos-arm64/arm64-macos.dylib"), plan.units().get(0).outputPath());
        assertEquals(2, plan.targetPreflights().size());
        assertEquals(List.of("linux-x64", "macos-arm64"), plan.targetPreflights().stream()
                .map(preflight -> preflight.target().directoryName())
                .toList());
        assertFalse(plan.targetPreflights().get(0).buildable());
        assertEquals("failed", plan.targetPreflights().get(0).status());
        assertEquals("ZIG_TARGET_UNBUILDABLE", plan.targetPreflights().get(0).reasonCode());
        assertTrue(plan.targetPreflights().get(0).required());
        assertEquals("unsupportedLibc", plan.targetPreflights().get(0).failureKind());
        assertTrue(plan.targetPreflights().get(0).buildLogTail().contains("no Zig build invoked"));
        assertTrue(plan.targetPreflights().get(1).buildable());
        assertEquals("CURRENT_HOST_TARGET", plan.targetPreflights().get(1).reasonCode());
        assertEquals("none", plan.targetPreflights().get(1).failureKind());
    }

    @Test
    void recordsUnsupportedHostAsFailedRequiredPreflight() {
        NativeBuildPlan plan = new NativeBuildPlanner(Optional.empty()).plan(
                Path.of("/work"),
                "j2llapp",
                List.of(TargetTriple.LINUX_X64));

        assertTrue(plan.units().isEmpty());
        assertTrue(plan.skippedTargetPreflights().isEmpty());
        assertEquals(1, plan.failedTargetPreflights().size());
        assertEquals("ZIG_TARGET_UNBUILDABLE", plan.failedTargetPreflights().get(0).reasonCode());
        assertEquals("unknown", plan.failedTargetPreflights().get(0).failureKind());
        assertEquals(Path.of("/work/native/linux-x64/x64-linux.so"),
                plan.failedTargetPreflights().get(0).outputPath());
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
                Path.of("/work/native/linux-arm64/arm64-linux.so"), "unsupportedLibc");
        assertTarget(plan.targetPreflights().get(1), TargetTriple.LINUX_X64, "linux", "x64", "so",
                Path.of("/work/native/linux-x64/x64-linux.so"), "unsupportedLibc");
        assertTarget(plan.targetPreflights().get(2), TargetTriple.MACOS_ARM64, "macos", "arm64", "dylib",
                Path.of("/work/native/macos-arm64/arm64-macos.dylib"), "none");
        assertTarget(plan.targetPreflights().get(3), TargetTriple.MACOS_X64, "macos", "x64", "dylib",
                Path.of("/work/native/macos-x64/x64-macos.dylib"), "missingSdk");
        assertTarget(plan.targetPreflights().get(4), TargetTriple.WINDOWS_ARM64, "windows", "arm64", "dll",
                Path.of("/work/native/windows-arm64/arm64-windows.dll"), "unsupportedLinker");
        assertTarget(plan.targetPreflights().get(5), TargetTriple.WINDOWS_X64, "windows", "x64", "dll",
                Path.of("/work/native/windows-x64/x64-windows.dll"), "unsupportedLinker");
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
        assertTrue(preflight.buildLogTail().contains(target.directoryName()) || preflight.currentHost());
    }
}
