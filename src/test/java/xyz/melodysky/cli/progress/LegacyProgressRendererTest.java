package xyz.melodysky.cli.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import xyz.melodysky.progress.BuildStage;

class LegacyProgressRendererTest {
    @Test
    void redirectedOutputUsesOnePlainLinePerStageWithoutControlCharacters() {
        AtomicLong now = new AtomicLong();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        LegacyProgressRenderer renderer = renderer(bytes, false, 100, now);

        renderer.stageStarted(
                BuildStage.INPUT_INSPECTION,
                "input.jar\u001B[2J\r\nignored");
        renderer.stageProgress(
                BuildStage.INPUT_INSPECTION,
                1,
                2,
                "not emitted in plain mode");
        renderer.stageStarted(BuildStage.CLASS_PARSING, "3 classes");
        now.set(2_000_000_000L);
        renderer.finished(true);

        String output = bytes.toString(StandardCharsets.UTF_8);
        String normalized = output.replace("\r\n", "\n");
        assertTrue(output.contains("[01/13] Inspecting input  input.jar ignored"), output);
        assertTrue(output.contains("[02/13] Parsing classes  3 classes"), output);
        assertTrue(output.contains("BUILD SUCCESSFUL in 2s"), output);
        assertFalse(output.contains("actionable stages"), output);
        assertFalse(normalized.contains("\r"), output);
        assertFalse(output.contains("\u001B"), output);
        assertFalse(output.contains("not emitted"), output);
    }

    @Test
    void interactiveOutputShowsHonestPerTargetNativeProgressAndCollapsesOnCompletion() {
        AtomicLong now = new AtomicLong();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        LegacyProgressRenderer renderer = renderer(bytes, true, 120, now);

        renderer.stageStarted(BuildStage.INPUT_INSPECTION, "input.jar");
        renderer.stageStarted(BuildStage.METHOD_LOWERING, "2 methods");
        renderer.stageProgress(BuildStage.METHOD_LOWERING, 2, 2, "done");
        renderer.stageStarted(BuildStage.NATIVE_PLANNING, "2 lowered methods");
        renderer.stageStarted(BuildStage.LLVM_EMISSION, "1 class");
        renderer.stageProgress(BuildStage.LLVM_EMISSION, 1, 1, "done");
        renderer.stageStarted(BuildStage.INTERMEDIATE_WRITING, "enabled");

        List<String> nativeScreen = TerminalScreen.render(bytes);
        assertEquals(5, nativeScreen.size(), nativeScreen.toString());
        assertTrue(nativeScreen.get(0).startsWith(
                "Read bytecode  [============================] done"), nativeScreen.toString());
        assertFalse(nativeScreen.get(0).contains("done  done"), nativeScreen.toString());
        assertTrue(nativeScreen.get(1).contains(
                "Lower to IR    [============================] 2/2  done"), nativeScreen.toString());
        assertTrue(nativeScreen.get(2).contains(
                "Emit LLVM IR   [============================] 1/1  done"), nativeScreen.toString());
        assertTrue(nativeScreen.get(3).startsWith(
                "Build native   [----------------------------] --"), nativeScreen.toString());
        assertTrue(nativeScreen.get(4).startsWith("Stage          preparing"), nativeScreen.toString());

        renderer.stageStarted(
                BuildStage.NATIVE_BUILD,
                "2 targets");
        renderer.nativeTargetsStarted(List.of("linux-arm64", "windows-x64"));
        List<String> buildingScreen = TerminalScreen.render(bytes);
        assertEquals(7, buildingScreen.size(), buildingScreen.toString());
        assertTrue(buildingScreen.get(3).contains("0/2  targets complete"), buildingScreen.toString());
        assertTrue(buildingScreen.get(4).startsWith("linux-arm64"), buildingScreen.toString());
        assertTrue(buildingScreen.get(4).contains("building/linking"), buildingScreen.toString());
        assertTrue(buildingScreen.get(5).startsWith("windows-x64"), buildingScreen.toString());
        assertTrue(buildingScreen.get(5).contains("building/linking"), buildingScreen.toString());
        assertTrue(buildingScreen.get(6).startsWith("Stage          building"), buildingScreen.toString());
        assertFalse(buildingScreen.get(3).contains("0/0"), buildingScreen.toString());

        renderer.nativeTargetCompleted("windows-x64");
        List<String> oneCompletedScreen = TerminalScreen.render(bytes);
        assertTrue(oneCompletedScreen.get(3).contains("1/2  targets complete"), oneCompletedScreen.toString());
        assertTrue(oneCompletedScreen.get(4).contains("building/linking"), oneCompletedScreen.toString());
        assertTrue(oneCompletedScreen.get(5).endsWith("done"), oneCompletedScreen.toString());

        renderer.nativeTargetCompleted("linux-arm64");
        List<String> allCompletedScreen = TerminalScreen.render(bytes);
        assertTrue(allCompletedScreen.get(3).contains(
                "[============================] 2/2  targets complete"), allCompletedScreen.toString());
        assertTrue(allCompletedScreen.get(6).startsWith("Stage          finishing"), allCompletedScreen.toString());

        renderer.stageStarted(BuildStage.JAR_PACKAGING, "output.jar");
        renderer.stageStarted(BuildStage.ARTIFACT_AUDIT, "output.jar");
        renderer.stageStarted(BuildStage.REPORT_WRITING, "reports");
        now.set(3_000_000_000L);
        renderer.finished(true);

        List<String> completedScreen = TerminalScreen.render(bytes);
        assertEquals(6, completedScreen.size(), completedScreen.toString());
        assertTrue(completedScreen.get(3).startsWith(
                "Build native   [============================] 2/2  done"), completedScreen.toString());
        assertTrue(completedScreen.get(4).contains(
                "Finalize JAR   [============================] 3/3  done"), completedScreen.toString());
        assertEquals("BUILD SUCCESSFUL in 3s", completedScreen.get(5));
        assertFalse(completedScreen.stream().anyMatch(line -> line.startsWith("linux-arm64")));
        assertFalse(completedScreen.stream().anyMatch(line -> line.startsWith("windows-x64")));
    }

    @Test
    void redirectedOutputDoesNotSpamNativeTargetCompletionLines() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        LegacyProgressRenderer renderer = renderer(bytes, false, 100, new AtomicLong());

        renderer.stageStarted(BuildStage.NATIVE_BUILD, "2 targets");
        renderer.nativeTargetsStarted(List.of("linux-arm64", "windows-x64"));
        renderer.nativeTargetCompleted("windows-x64");
        renderer.nativeTargetCompleted("linux-arm64");
        renderer.finished(true);

        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("[10/13] Building native libraries  2 targets"), output);
        assertFalse(output.contains("linux-arm64"), output);
        assertFalse(output.contains("windows-x64"), output);
        assertEquals(2, output.replace("\r\n", "\n").lines().count(), output);
    }

    @Test
    void methodProgressShowsOneRealCountAndKeepsCurrentMethodTail() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        LegacyProgressRenderer renderer = renderer(bytes, true, 120, new AtomicLong());

        renderer.stageStarted(BuildStage.METHOD_LOWERING, "2 methods");
        renderer.stageProgress(
                BuildStage.METHOD_LOWERING,
                1,
                2,
                "pkg/Foo#second!()V");

        List<String> screen = TerminalScreen.render(bytes);
        String lowerLine = screen.get(1);
        assertTrue(lowerLine.startsWith(
                "Lower to IR    [=============>              ] 1/2"), screen.toString());
        assertEquals(1, occurrences(lowerLine, "1/2"), lowerLine);
        assertTrue(lowerLine.endsWith("pkg/Foo#second!()V"), lowerLine);
    }

    @Test
    void zeroWorkIsReportedHonestlyWithoutInventingOneUnit() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        LegacyProgressRenderer renderer = renderer(bytes, true, 120, new AtomicLong());

        renderer.stageStarted(BuildStage.METHOD_LOWERING, "0 methods");
        renderer.stageProgress(
                BuildStage.METHOD_LOWERING,
                0,
                0,
                "no methods selected");

        String lowerLine = TerminalScreen.render(bytes).get(1);
        assertTrue(lowerLine.contains("[============================] 0/0"), lowerLine);
        assertFalse(lowerLine.contains("0/1"), lowerLine);
    }

    @Test
    void failureOnlyClearsActiveRegionBeforePrimaryDiagnostic() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream output = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        LegacyProgressRenderer renderer = new LegacyProgressRenderer(
                output,
                true,
                120,
                () -> 0L);

        renderer.stageStarted(BuildStage.METHOD_LOWERING, "2 methods");
        renderer.stageProgress(
                BuildStage.METHOD_LOWERING,
                0,
                2,
                "pkg/Foo#first!()V");
        renderer.finished(false);
        output.println("FRONTEND SSA_INVALID: failed to lower method");

        String raw = bytes.toString(StandardCharsets.UTF_8);
        List<String> screen = TerminalScreen.render(bytes);
        assertEquals(
                List.of("FRONTEND SSA_INVALID: failed to lower method"),
                screen,
                screen.toString());
        assertFalse(raw.contains("BUILD FAILED"), raw);
        assertFalse(raw.contains("actionable stages"), raw);
        int diagnostic = raw.indexOf("FRONTEND SSA_INVALID");
        assertTrue(raw.lastIndexOf("\u001B") < diagnostic, raw);
    }

    @Test
    void interactiveOutputAdaptsBarsBeforeTruncatingUsefulNarrowTerminalText() {
        assertInteractiveRegionFits(
                32,
                "pkg/very/long/Foo#方法名称!()V\u001B[2J",
                "Lower to IR");
        assertInteractiveRegionFits(
                40,
                "pkg/very/long/Foo#方法名称!()V",
                "Lower to IR");
        assertInteractiveRegionFits(
                72,
                "pkg/very/long/Foo#方法名称!()V",
                "方法名称!()V");
        assertInteractiveRegionFits(
                120,
                "pkg/very/long/Foo#方法名称!()V",
                "[============================]");
    }

    @Test
    void narrowNativeRegionKeepsTargetNamesAndRealAggregateCount() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        LegacyProgressRenderer renderer = renderer(bytes, true, 32, new AtomicLong());

        renderer.stageStarted(BuildStage.NATIVE_BUILD, "2 targets");
        renderer.nativeTargetsStarted(List.of("linux-arm64", "windows-x64"));
        renderer.nativeTargetCompleted("windows-x64");

        List<String> lines = TerminalScreen.render(bytes);
        assertEquals(4, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains("1/2"), lines.toString());
        assertTrue(lines.get(1).contains("linux-arm64"), lines.toString());
        assertTrue(lines.get(1).contains("building/linking"), lines.toString());
        assertTrue(lines.get(2).contains("windows-x64"), lines.toString());
        assertTrue(lines.get(2).contains("done"), lines.toString());
        assertTrue(lines.stream().allMatch(line -> TerminalText.displayWidth(line) <= 32), lines.toString());
    }

    @Test
    void finishAndLateEventsAreIgnoredAfterFirstCompletion() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        LegacyProgressRenderer renderer = renderer(bytes, false, 100, new AtomicLong());

        renderer.stageStarted(BuildStage.REPORT_WRITING, "reports");
        renderer.finished(true);
        renderer.finished(false);
        renderer.stageStarted(BuildStage.INPUT_INSPECTION, "late.jar");

        String output = bytes.toString(StandardCharsets.UTF_8);
        assertEquals(1, occurrences(output, "BUILD SUCCESSFUL"));
        assertFalse(output.contains("BUILD FAILED"), output);
        assertFalse(output.contains("late.jar"), output);
    }

    private static LegacyProgressRenderer renderer(
            ByteArrayOutputStream bytes,
            boolean interactive,
            int width,
            AtomicLong now) {
        return new LegacyProgressRenderer(
                new PrintStream(bytes, true, StandardCharsets.UTF_8),
                interactive,
                width,
                now::get);
    }

    private static void assertInteractiveRegionFits(
            int width,
            String detail,
            String expectedText) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        LegacyProgressRenderer renderer = renderer(bytes, true, width, new AtomicLong());
        renderer.stageStarted(BuildStage.METHOD_LOWERING, "1 method");
        renderer.stageProgress(BuildStage.METHOD_LOWERING, 0, 1, detail);

        List<String> lines = TerminalScreen.render(bytes);
        assertEquals(3, lines.size(), lines.toString());
        for (String line : lines) {
            assertTrue(TerminalText.displayWidth(line) <= width, lines.toString());
        }
        assertTrue(lines.stream().anyMatch(line -> line.contains(expectedText)), lines.toString());
        assertFalse(lines.stream().anyMatch(line -> line.contains("[2J")), lines.toString());
    }

    private static int occurrences(String value, String token) {
        return value.split(java.util.regex.Pattern.quote(token), -1).length - 1;
    }

    private static final class TerminalScreen {
        private final ArrayList<StringBuilder> lines = new ArrayList<>(List.of(new StringBuilder()));
        private int row;
        private int column;

        static List<String> render(ByteArrayOutputStream bytes) {
            TerminalScreen screen = new TerminalScreen();
            screen.accept(bytes.toString(StandardCharsets.UTF_8));
            return screen.visibleLines();
        }

        private void accept(String output) {
            for (int index = 0; index < output.length();) {
                char character = output.charAt(index);
                if (character == '\r') {
                    column = 0;
                    index++;
                } else if (character == '\n') {
                    row++;
                    column = 0;
                    ensureRow();
                    index++;
                } else if (character == '\u001B'
                        && output.startsWith("\u001B[2K", index)) {
                    lines.get(row).setLength(0);
                    column = 0;
                    index += 4;
                } else if (character == '\u001B'
                        && output.startsWith("\u001B[1A", index)) {
                    row = Math.max(0, row - 1);
                    column = 0;
                    index += 4;
                } else {
                    write(character);
                    index++;
                }
            }
        }

        private void write(char character) {
            ensureRow();
            StringBuilder line = lines.get(row);
            while (line.length() < column) {
                line.append(' ');
            }
            if (column < line.length()) {
                line.setCharAt(column, character);
            } else {
                line.append(character);
            }
            column++;
        }

        private void ensureRow() {
            while (lines.size() <= row) {
                lines.add(new StringBuilder());
            }
        }

        private List<String> visibleLines() {
            ArrayList<String> result = new ArrayList<>();
            for (StringBuilder line : lines) {
                result.add(line.toString().stripTrailing());
            }
            while (!result.isEmpty() && result.getLast().isEmpty()) {
                result.removeLast();
            }
            return List.copyOf(result);
        }
    }
}
