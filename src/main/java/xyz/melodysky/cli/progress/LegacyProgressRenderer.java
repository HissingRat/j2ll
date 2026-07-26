package xyz.melodysky.cli.progress;

import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.fusesource.jansi.AnsiConsole;
import org.fusesource.jansi.AnsiMode;
import org.fusesource.jansi.AnsiPrintStream;
import org.fusesource.jansi.AnsiType;
import xyz.melodysky.progress.BuildProgressListener;
import xyz.melodysky.progress.BuildStage;
import xyz.melodysky.progress.NativeTargetProgress;
import xyz.melodysky.progress.NativeTargetState;

public final class LegacyProgressRenderer implements BuildProgressListener {
    private static final int DEFAULT_TERMINAL_WIDTH = 120;
    private static final int MAX_TERMINAL_WIDTH = 240;
    private static final long MIN_RENDER_INTERVAL_NANOS = 100_000_000L;

    private final PrintStream output;
    private final boolean interactive;
    private final int terminalWidth;
    private final LongSupplier nanoTime;
    private final long startedAtNanos;
    private final LegacyProgressLayout layout = new LegacyProgressLayout();
    private final AnsiProgressRegion region;

    private BuildStage currentStage;
    private LegacyProgressLayout.Phase currentPhase;
    private String detail = "";
    private boolean currentWorkKnown;
    private long workCompleted;
    private long workTotal;
    private LegacyProgressLayout.Work methodWork = LegacyProgressLayout.Work.unknown();
    private LegacyProgressLayout.Work llvmWork = LegacyProgressLayout.Work.unknown();
    private final LinkedHashMap<String, NativeTargetProgress> nativeTargets = new LinkedHashMap<>();
    private long lastRenderAtNanos = Long.MIN_VALUE;
    private boolean renderedWorkForCurrentStage;
    private boolean finished;

    public static LegacyProgressRenderer forCli(PrintStream output) {
        Objects.requireNonNull(output, "output");
        boolean interactive = supportsInteractiveTerminal(output);
        int detectedWidth = interactive ? terminalWidth() : -1;
        int width = detectedWidth > 0 ? detectedWidth : DEFAULT_TERMINAL_WIDTH;
        return new LegacyProgressRenderer(output, interactive, width, System::nanoTime);
    }

    LegacyProgressRenderer(
            PrintStream output,
            boolean interactive,
            int terminalWidth,
            LongSupplier nanoTime) {
        this.output = Objects.requireNonNull(output, "output");
        this.interactive = interactive;
        this.terminalWidth = Math.max(1, Math.min(MAX_TERMINAL_WIDTH, terminalWidth));
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.startedAtNanos = nanoTime.getAsLong();
        this.region = interactive ? new AnsiProgressRegion(output, this.terminalWidth) : null;
    }

    @Override
    public synchronized void stageStarted(BuildStage stage, String detail) {
        if (finished) {
            return;
        }
        BuildStage nextStage = Objects.requireNonNull(stage, "stage");
        LegacyProgressLayout.Phase nextPhase = layout.phaseFor(nextStage);
        if (interactive && currentPhase != null && currentPhase != nextPhase) {
            region.complete(layout.completedLines(currentPhase, view(), terminalWidth));
        }

        currentStage = nextStage;
        currentPhase = nextPhase;
        this.detail = TerminalText.sanitize(detail);
        currentWorkKnown = false;
        workCompleted = 0L;
        workTotal = 0L;
        renderedWorkForCurrentStage = false;
        if (stage == BuildStage.NATIVE_BUILD) {
            nativeTargets.clear();
        }

        if (interactive) {
            renderInteractive(nanoTime.getAsLong());
        } else {
            output.println(plainStageLine(stage, this.detail));
        }
    }

    @Override
    public synchronized void nativeTargetsStarted(List<String> targets) {
        if (finished || currentStage != BuildStage.NATIVE_BUILD) {
            return;
        }
        nativeTargets.clear();
        if (targets != null) {
            for (String target : targets) {
                String sanitized = TerminalText.sanitize(target);
                if (!sanitized.isBlank()) {
                    nativeTargets.putIfAbsent(
                            sanitized,
                            NativeTargetProgress.building(sanitized));
                }
            }
        }
        if (interactive) {
            renderInteractive(nanoTime.getAsLong());
        }
    }

    @Override
    public synchronized void nativeTargetProgress(NativeTargetProgress progress) {
        if (finished || currentStage != BuildStage.NATIVE_BUILD) {
            return;
        }
        if (progress == null) {
            return;
        }
        String sanitized = TerminalText.sanitize(progress.target());
        if (sanitized.isBlank()
                || !nativeTargets.containsKey(sanitized)) {
            return;
        }
        NativeTargetProgress current = nativeTargets.get(sanitized);
        NativeTargetProgress next = progress.withTarget(sanitized);
        if (!advances(current, next)) {
            return;
        }
        nativeTargets.put(sanitized, next);
        if (interactive) {
            renderInteractive(nanoTime.getAsLong());
        }
    }

    @Override
    public synchronized void nativeTargetCompleted(String target) {
        if (finished || currentStage != BuildStage.NATIVE_BUILD) {
            return;
        }
        String sanitized = TerminalText.sanitize(target);
        NativeTargetProgress current = nativeTargets.get(sanitized);
        if (current == null || current.completed()) {
            return;
        }
        long total = current.totalUnits();
        nativeTargetProgress(new NativeTargetProgress(
                sanitized,
                NativeTargetState.COMPLETED,
                total,
                total));
    }

    @Override
    public synchronized void stageProgress(
            BuildStage stage,
            long completed,
            long total,
            String detail) {
        if (finished || currentStage != stage) {
            return;
        }
        currentWorkKnown = true;
        workCompleted = Math.max(0L, completed);
        workTotal = Math.max(0L, total);
        this.detail = TerminalText.sanitize(detail);
        if (stage == BuildStage.METHOD_LOWERING) {
            methodWork = methodWork.update(workCompleted, workTotal, this.detail);
        } else if (stage == BuildStage.LLVM_EMISSION) {
            llvmWork = llvmWork.update(workCompleted, workTotal, this.detail);
        }
        if (!interactive) {
            return;
        }

        long now = nanoTime.getAsLong();
        boolean completedCurrentWork = total >= 0L && completed >= total;
        if (!renderedWorkForCurrentStage
                || completedCurrentWork
                || now - lastRenderAtNanos >= MIN_RENDER_INTERVAL_NANOS) {
            renderInteractive(now);
            renderedWorkForCurrentStage = true;
        }
    }

    @Override
    public synchronized void finished(boolean successful) {
        if (finished) {
            return;
        }
        finished = true;
        long now = nanoTime.getAsLong();
        if (interactive) {
            if (successful && currentPhase != null) {
                region.complete(layout.completedLines(currentPhase, view(), terminalWidth));
            } else {
                region.clear();
            }
        }
        if (successful) {
            output.println("BUILD SUCCESSFUL in " + elapsedSeconds(now) + "s");
        }
        output.flush();
    }

    private void renderInteractive(long now) {
        region.update(layout.activeLines(view(), terminalWidth));
        lastRenderAtNanos = now;
    }

    private LegacyProgressLayout.View view() {
        return new LegacyProgressLayout.View(
                currentStage,
                detail,
                methodWork,
                llvmWork,
                List.copyOf(nativeTargets.values()));
    }

    private boolean advances(
            NativeTargetProgress current,
            NativeTargetProgress next) {
        if (current.completed()
                || next.state().ordinal() < current.state().ordinal()) {
            return false;
        }
        if (next.state() == current.state()
                && next.completedUnits() < current.completedUnits()) {
            return false;
        }
        return !next.equals(current);
    }

    private String plainStageLine(BuildStage stage, String stageDetail) {
        int totalStages = BuildStage.values().length;
        int digits = Integer.toString(totalStages).length();
        String prefix = String.format(
                Locale.ROOT,
                "[%0" + digits + "d/%0" + digits + "d] %s",
                stage.ordinal() + 1,
                totalStages,
                stage.displayName());
        return stageDetail.isBlank() ? prefix : prefix + "  " + stageDetail;
    }

    private long elapsedSeconds(long now) {
        return Math.max(0L, (now - startedAtNanos) / 1_000_000_000L);
    }

    private static int terminalWidth() {
        try {
            int width = AnsiConsole.err().getTerminalWidth();
            if (width > 1) {
                return width - 1;
            }
        } catch (Throwable ignored) {
        }
        String columns = System.getenv("COLUMNS");
        if (columns != null && !columns.isBlank()) {
            try {
                int width = Integer.parseInt(columns);
                if (width > 1) {
                    return width - 1;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    private static boolean supportsInteractiveTerminal(PrintStream output) {
        String ci = System.getenv("CI");
        if (output != System.err
                || !AnsiConsole.isInstalled()
                || (ci != null && !ci.isBlank())
                || "dumb".equalsIgnoreCase(System.getenv("TERM"))) {
            return false;
        }
        try {
            AnsiPrintStream terminal = AnsiConsole.err();
            AnsiType type = terminal.getType();
            return terminal.getMode() != AnsiMode.Strip
                    && type != AnsiType.Redirected
                    && type != AnsiType.Unsupported;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
