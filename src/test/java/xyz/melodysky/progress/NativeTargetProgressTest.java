package xyz.melodysky.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NativeTargetProgressTest {
    @Test
    void exposesRealUnitsAndDerivedPercentage() {
        NativeTargetProgress progress = new NativeTargetProgress(
                "linux-x64",
                NativeTargetState.BUILDING,
                1,
                3);

        assertEquals(1, progress.completedUnits());
        assertEquals(3, progress.totalUnits());
        assertEquals(33, progress.percentage());
        assertTrue(progress.hasKnownUnits());
        assertFalse(progress.completed());
    }

    @Test
    void percentageUsesStableFloorRounding() {
        NativeTargetProgress progress = new NativeTargetProgress(
                "macos-arm64",
                NativeTargetState.BUILDING,
                2,
                3);

        assertEquals(66, progress.percentage());
    }

    @Test
    void completedStateIsCompleteEvenWhenLegacySourceHasNoUnitCount() {
        NativeTargetProgress progress = new NativeTargetProgress(
                "windows-x64",
                NativeTargetState.COMPLETED,
                0,
                0);

        assertEquals(100, progress.percentage());
        assertFalse(progress.hasKnownUnits());
        assertTrue(progress.completed());
    }
}
