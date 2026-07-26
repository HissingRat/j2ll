package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ZigBuildProgressPlanTest {
    @Test
    void capsLargeSourceSetsWithBalancedDeterministicProgressUnits() {
        List<Path> cSources = IntStream.range(0, 101)
                .mapToObj(index -> Path.of("source-" + index + ".c"))
                .toList();
        NativeBuildPlan buildPlan = new NativeBuildPlan(List.of(new NativeBuildUnit(
                TargetTriple.LINUX_X64,
                Path.of("native", TargetTriple.LINUX_X64.libraryFileName()),
                "j2lltest")));
        ZigSourceSet sources = new ZigSourceSet(List.of(), cSources, List.of(), List.of());

        ZigBuildProgressPlan.TargetPlan target =
                ZigBuildProgressPlan.forSources(buildPlan, sources).targets().get(0);

        assertEquals(ZigBuildProgressPlan.MAX_COMPILE_UNITS, target.compileUnits().size());
        assertEquals(101, target.compileUnits().stream()
                .mapToInt(unit -> unit.inputs().size())
                .sum());
        assertTrue(target.compileUnits().stream()
                .allMatch(unit -> unit.inputs().size() == 1 || unit.inputs().size() == 2));
        assertEquals("batch-0", target.compileUnits().get(0).id());
        assertEquals(
                "batch-" + (ZigBuildProgressPlan.MAX_COMPILE_UNITS - 1),
                target.compileUnits().get(target.compileUnits().size() - 1).id());
        assertEquals(ZigBuildProgressPlan.MAX_COMPILE_UNITS + 1, target.totalUnits());
    }
}
