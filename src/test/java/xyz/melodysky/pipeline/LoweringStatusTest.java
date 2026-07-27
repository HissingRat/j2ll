package xyz.melodysky.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class LoweringStatusTest {
    @Test
    void selectedMethodStatusModelHasExactlyTwoOutcomes() {
        assertEquals(
                List.of(
                        LoweringStatus.NATIVE_LOWERED,
                        LoweringStatus.SKIPPED),
                List.of(LoweringStatus.values()));
        assertThrows(
                IllegalArgumentException.class,
                () -> LoweringStatus.fromWireName("excluded"));
    }
}
