package xyz.melodysky.app;

import xyz.melodysky.config.BuildTarget;
import xyz.melodysky.console.ConsoleProgressDisplay;
import xyz.melodysky.pipeline.IrPipelineCompiler;
import xyz.melodysky.toolchain.IrNativeBuildDriver;

import java.util.List;
import java.util.Locale;

public final class ConsoleProgressAdapters {

    private ConsoleProgressAdapters() {
    }

    public static PipelineConsoleProgress pipeline(ConsoleProgressDisplay display) {
        return new PipelineConsoleProgress(display);
    }

    public static NativeBuildConsoleProgress nativeBuild(ConsoleProgressDisplay display) {
        return new NativeBuildConsoleProgress(display);
    }

    public static final class PipelineConsoleProgress implements IrPipelineCompiler.ProgressListener {
        private static final int WIDTH = 28;
        private static final long MIN_RENDER_INTERVAL_MS = 100L;

        private final ConsoleProgressDisplay display;
        private int readTotal;
        private int readCurrent;
        private String readName = "waiting";
        private int lowerTotal;
        private int lowerCurrent;
        private String lowerName = "waiting";
        private int llvmTotal;
        private int llvmCurrent;
        private String llvmName = "waiting";
        private long lastRenderAt;

        private PipelineConsoleProgress(ConsoleProgressDisplay display) {
            this.display = display;
        }

        @Override
        public void onBytecodeReadStart(int totalClasses) {
            readTotal = totalClasses;
            render(true);
        }

        @Override
        public void onBytecodeReadProgress(int current, int totalClasses, String className) {
            readCurrent = current;
            readTotal = totalClasses;
            readName = abbreviateClassName(className);
            render(current >= totalClasses);
        }

        @Override
        public void onIrLowerStart(int totalClasses) {
            lowerTotal = totalClasses;
            render(true);
        }

        @Override
        public void onIrLowerProgress(int current, int totalClasses, String className) {
            lowerCurrent = current;
            lowerTotal = totalClasses;
            lowerName = abbreviateClassName(className);
            render(current >= totalClasses);
        }

        @Override
        public void onLlvmEmitStart(int totalClasses) {
            llvmTotal = totalClasses;
            render(true);
        }

        @Override
        public void onLlvmEmitProgress(int current, int totalClasses, String className) {
            llvmCurrent = current;
            llvmTotal = totalClasses;
            llvmName = abbreviateClassName(className);
            render(current >= totalClasses);
        }

        private void render(boolean force) {
            long now = System.currentTimeMillis();
            if (!force && now - lastRenderAt < MIN_RENDER_INTERVAL_MS) {
                return;
            }
            lastRenderAt = now;
            display.updateLines(List.of(
                    formatLine("Read bytecode", readCurrent, readTotal, readName),
                    formatLine("Lower to IR", lowerCurrent, lowerTotal, lowerName),
                    formatLine("Emit LLVM IR", llvmCurrent, llvmTotal, llvmName)
            ));
        }

        private String formatLine(String label, int current, int total, String name) {
            return String.format(Locale.ROOT, "%-14s %s %d/%d  %s",
                    label,
                    display.formatProgressBar(current, total, WIDTH),
                    current, Math.max(total, current),
                    name);
        }

        public void finish() {
            readCurrent = Math.max(readCurrent, readTotal);
            lowerCurrent = Math.max(lowerCurrent, lowerTotal);
            llvmCurrent = Math.max(llvmCurrent, llvmTotal);
            readName = "done";
            lowerName = "done";
            llvmName = "done";
            display.completeLines(List.of(
                    formatLine("Read bytecode", readCurrent, readTotal, readName),
                    formatLine("Lower to IR", lowerCurrent, lowerTotal, lowerName),
                    formatLine("Emit LLVM IR", llvmCurrent, llvmTotal, llvmName)
            ));
        }
    }

    public static final class NativeBuildConsoleProgress implements IrNativeBuildDriver.ProgressListener {
        private static final int WIDTH = 28;

        private final ConsoleProgressDisplay display;
        private String targetName = "idle";
        private int compileCompleted;
        private int compileTotal;
        private String compileLabel = "waiting";
        private String stage = "starting";

        private NativeBuildConsoleProgress(ConsoleProgressDisplay display) {
            this.display = display;
        }

        @Override
        public void onTargetStart(BuildTarget target, int totalUnits) {
            targetName = target.getConfigKey();
            compileCompleted = 0;
            compileTotal = totalUnits;
            compileLabel = "starting";
            stage = "compiling";
            render();
        }

        @Override
        public void onCompileProgress(BuildTarget target, int completedUnits, int totalUnits, String unitLabel) {
            targetName = target.getConfigKey();
            compileCompleted = completedUnits;
            compileTotal = totalUnits;
            compileLabel = unitLabel;
            stage = "compiling";
            render();
        }

        @Override
        public void onLinkStart(BuildTarget target) {
            targetName = target.getConfigKey();
            stage = "linking";
            compileLabel = "zig build " + target.getConfigKey();
            render();
        }

        @Override
        public void onTargetComplete(BuildTarget target, IrNativeBuildDriver.BuildTiming timing) {
            targetName = target.getConfigKey();
            compileCompleted = compileTotal;
            stage = "done";
            compileLabel = "compile=" + timing.compileMillis() + "ms link=" + timing.linkMillis() + "ms";
            render();
        }

        private void render() {
            display.updateLines(List.of(
                    String.format(Locale.ROOT, "Build native   %s %d/%d  %s",
                            display.formatProgressBar(compileCompleted, compileTotal, WIDTH),
                            compileCompleted, Math.max(compileTotal, compileCompleted),
                            targetName),
                    String.format(Locale.ROOT, "Stage          %-10s %s", stage, compileLabel)
            ));
        }

        public void finish() {
            compileCompleted = Math.max(compileCompleted, compileTotal);
            stage = "done";
            compileLabel = "done";
            display.completeLines(List.of(
                    String.format(Locale.ROOT, "Build native   %s %d/%d  %s",
                            display.formatProgressBar(compileCompleted, compileTotal, WIDTH),
                            compileCompleted, Math.max(compileTotal, compileCompleted),
                            targetName),
                    String.format(Locale.ROOT, "Stage          %-10s %s", stage, compileLabel)
            ));
        }
    }

    private static String abbreviateClassName(String className) {
        if (className == null || className.isBlank()) {
            return "waiting";
        }
        String normalized = className.replace('\\', '/');
        if (normalized.length() <= 72) {
            return normalized;
        }
        return "..." + normalized.substring(normalized.length() - 69);
    }
}
