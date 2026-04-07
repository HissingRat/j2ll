package xyz.melodysky;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MainTest {

    @Test
    public void testFormatIrOutputHintNormalizesWindowsSeparators() {
        String outputHint = Main.formatIrOutputHint(Path.of("out", "build_2026-04-04_10-00-00", "program.ll"));

        assertEquals("out/build_2026-04-04_10-00-00/program.ll", outputHint);
    }

    @Test
    public void testTreatsInterruptedExceptionAsCancellation() {
        assertTrue(Main.isCancellation(new InterruptedException("cancelled")));
    }

    @Test
    public void testTreatsCancellationExceptionAsCancellation() {
        assertTrue(Main.isCancellation(new CancellationException("cancelled")));
    }
}
