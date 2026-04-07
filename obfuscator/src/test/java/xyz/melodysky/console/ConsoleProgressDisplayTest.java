package xyz.melodysky.console;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConsoleProgressDisplayTest {

    @Test
    public void testTreatsClassicWindowsConsoleAsNonAnsiByDefault() {
        assertFalse(ConsoleProgressDisplay.isAnsiCapableConsole(true, "Windows 10", Map.of(), false));
    }

    @Test
    public void testTreatsWindowsTerminalAsAnsiCapable() {
        assertTrue(ConsoleProgressDisplay.isAnsiCapableConsole(true, "Windows 10", Map.of("WT_SESSION", "1"), false));
    }

    @Test
    public void testTreatsUnixConsoleAsAnsiCapable() {
        assertTrue(ConsoleProgressDisplay.isAnsiCapableConsole(true, "Linux", Map.of(), false));
    }

    @Test
    public void testTreatsJansiBackedWindowsConsoleAsAnsiCapable() {
        assertTrue(ConsoleProgressDisplay.isAnsiCapableConsole(true, "Windows 10", Map.of(), true));
    }

    @Test
    public void testSummarizesFallbackLinesWithinWidthBudget() {
        ConsoleProgressDisplay display = new ConsoleProgressDisplay();
        String summary = display.summarizeFallbackLinesForTest(
                java.util.List.of(
                        "Read bytecode  [============================] 6974/6974  bi$1",
                        "Lower to IR    [=======>                    ] 1853/6584  sample/Foo",
                        "Emit LLVM IR   [----------------------------] 0/6584  waiting"
                ),
                96
        );

        assertTrue(summary.length() <= 96);
        assertTrue(summary.contains("Read bytecode"));
        assertTrue(summary.contains("Lower to IR"));
    }

    @Test
    public void testTruncatesAnsiLinesToAvoidTerminalWrap() {
        ConsoleProgressDisplay display = new ConsoleProgressDisplay();
        java.util.List<String> lines = display.truncateLinesToWidthForTest(
                java.util.List.of("Read bytecode  [============================] 6974/6974  bi$1  some/very/long/path/that/keeps/going"),
                48
        );

        assertTrue(lines.getFirst().length() <= 48);
    }
}
