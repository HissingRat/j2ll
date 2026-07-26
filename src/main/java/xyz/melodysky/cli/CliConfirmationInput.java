package xyz.melodysky.cli;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import org.fusesource.jansi.internal.CLibrary;

/** Selects a confirmation input without blocking unattended builds. */
final class CliConfirmationInput {
    private CliConfirmationInput() {
    }

    static InputStream systemInput() {
        return select(System.in, isInteractiveTerminal());
    }

    static InputStream select(InputStream input, boolean interactiveTerminal) {
        Objects.requireNonNull(input, "input");
        return interactiveTerminal || hasBufferedInput(input)
                ? input
                : InputStream.nullInputStream();
    }

    private static boolean isInteractiveTerminal() {
        if (System.console() != null) {
            return true;
        }
        try {
            return CLibrary.LOADED
                    && CLibrary.HAVE_ISATTY
                    && CLibrary.isatty(0) != 0;
        } catch (LinkageError | RuntimeException ignored) {
            return false;
        }
    }

    private static boolean hasBufferedInput(InputStream input) {
        try {
            return input.available() > 0;
        } catch (IOException ignored) {
            return false;
        }
    }
}
