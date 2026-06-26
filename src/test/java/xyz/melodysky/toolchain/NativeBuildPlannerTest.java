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
        assertEquals("NON_HOST_TARGET_PREFLIGHT_ONLY", plan.targetPreflights().get(0).reasonCode());
        assertTrue(plan.targetPreflights().get(1).buildable());
        assertEquals("CURRENT_HOST_TARGET", plan.targetPreflights().get(1).reasonCode());
    }

    @Test
    void recordsUnsupportedHostAsSkippedPreflight() {
        NativeBuildPlan plan = new NativeBuildPlanner(Optional.empty()).plan(
                Path.of("/work"),
                "j2llapp",
                List.of(TargetTriple.LINUX_X64));

        assertTrue(plan.units().isEmpty());
        assertEquals(1, plan.skippedTargetPreflights().size());
        assertEquals("UNSUPPORTED_HOST_PLATFORM", plan.skippedTargetPreflights().get(0).reasonCode());
        assertEquals(Path.of("/work/native/linux-x64/x64-linux.so"),
                plan.skippedTargetPreflights().get(0).outputPath());
    }
}
