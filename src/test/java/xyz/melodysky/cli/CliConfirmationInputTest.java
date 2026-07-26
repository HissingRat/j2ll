package xyz.melodysky.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CliConfirmationInputTest {
    @Test
    void interactiveTerminalKeepsInputEvenBeforeBytesAreAvailable() {
        InputStream input = InputStream.nullInputStream();

        assertSame(input, CliConfirmationInput.select(input, true));
    }

    @Test
    void preSuppliedPipeAnswerIsAcceptedWithoutATerminal() {
        InputStream input = new ByteArrayInputStream(
                "Y\n".getBytes(StandardCharsets.UTF_8));

        assertSame(input, CliConfirmationInput.select(input, false));
    }

    @Test
    void unattendedEmptyInputBecomesImmediateEof() throws Exception {
        InputStream selected = CliConfirmationInput.select(
                InputStream.nullInputStream(),
                false);

        assertEquals(-1, selected.read());
    }
}
