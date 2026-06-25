package xyz.melodysky.app;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class OutputPathFormatterTest {

    @Test
    public void testFormatIrOutputHintNormalizesWindowsSeparators() {
        String outputHint = OutputPathFormatter.formatIrOutputHint(Path.of("out", "build_2026-04-04_10-00-00", "program.ll"));

        assertEquals("out/build_2026-04-04_10-00-00/program.ll", outputHint);
    }
}
