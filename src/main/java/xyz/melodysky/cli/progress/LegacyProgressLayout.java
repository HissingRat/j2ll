package xyz.melodysky.cli.progress;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import xyz.melodysky.progress.BuildStage;
import xyz.melodysky.progress.NativePreparationStep;
import xyz.melodysky.progress.NativeTargetProgress;
import xyz.melodysky.progress.NativeTargetState;

final class LegacyProgressLayout {
    private static final int LABEL_WIDTH = 14;
    private static final int NORMAL_BAR_WIDTH = 28;
    private static final int MIN_BAR_WIDTH = 4;
    private static final int MIN_DETAIL_WIDTH = 8;

    enum Phase {
        COMPILER,
        NATIVE,
        FINALIZE
    }

    record Work(boolean known, long completed, long total, String detail) {
        static Work unknown() {
            return new Work(false, 0L, 0L, "");
        }

        Work update(long completed, long total, String detail) {
            return new Work(
                    true,
                    Math.max(0L, completed),
                    Math.max(0L, total),
                    TerminalText.sanitize(detail));
        }
    }

    record View(
            BuildStage stage,
            String detail,
            Work methodWork,
            Work llvmWork,
            Map<NativePreparationStep, Work> nativePreparationWork,
            boolean managedZigPreparation,
            NativePreparationStep activeNativePreparation,
            String nativePreparationDetail,
            List<NativeTargetProgress> nativeTargets) {
        View {
            detail = TerminalText.sanitize(detail);
            methodWork = methodWork == null ? Work.unknown() : methodWork;
            llvmWork = llvmWork == null ? Work.unknown() : llvmWork;
            nativePreparationWork = nativePreparationWork == null
                    ? Map.of()
                    : Map.copyOf(nativePreparationWork);
            nativePreparationDetail = TerminalText.sanitize(
                    nativePreparationDetail);
            nativeTargets = nativeTargets == null ? List.of() : List.copyOf(nativeTargets);
        }

        Work nativePreparationWork(NativePreparationStep step) {
            return nativePreparationWork.getOrDefault(step, Work.unknown());
        }
    }

    Phase phaseFor(BuildStage stage) {
        if (stage.ordinal() <= BuildStage.LLVM_EMISSION.ordinal()) {
            return Phase.COMPILER;
        }
        if (stage.ordinal() <= BuildStage.NATIVE_BUILD.ordinal()) {
            return Phase.NATIVE;
        }
        return Phase.FINALIZE;
    }

    List<String> activeLines(View view, int terminalWidth) {
        return switch (phaseFor(view.stage())) {
            case COMPILER -> compilerLines(view, terminalWidth, false);
            case NATIVE -> nativeLines(view, terminalWidth, false);
            case FINALIZE -> finalizeLines(view, terminalWidth, false);
        };
    }

    List<String> completedLines(Phase phase, View view, int terminalWidth) {
        return switch (phase) {
            case COMPILER -> compilerLines(view, terminalWidth, true);
            case NATIVE -> nativeLines(view, terminalWidth, true);
            case FINALIZE -> finalizeLines(view, terminalWidth, true);
        };
    }

    private List<String> compilerLines(View view, int width, boolean completed) {
        BuildStage stage = view.stage();
        String readLine = completed || stage.ordinal() >= BuildStage.METHOD_LOWERING.ordinal()
                ? doneLine("Read bytecode", -1L, "done", width, false)
                : indeterminateLine(
                        "Read bytecode",
                        stageDetail(stage, view.detail()),
                        width,
                        false);

        String lowerLine;
        if (completed || stage.ordinal() > BuildStage.METHOD_LOWERING.ordinal()) {
            lowerLine = doneWorkLine("Lower to IR", view.methodWork(), width, true);
        } else if (stage == BuildStage.METHOD_LOWERING) {
            lowerLine = workLine("Lower to IR", view.methodWork(), width, true);
        } else {
            lowerLine = indeterminateLine("Lower to IR", "waiting", width, false);
        }

        String llvmLine;
        if (completed) {
            llvmLine = doneWorkLine("Emit LLVM IR", view.llvmWork(), width, true);
        } else if (stage == BuildStage.LLVM_EMISSION) {
            llvmLine = workLine("Emit LLVM IR", view.llvmWork(), width, true);
        } else if (stage == BuildStage.NATIVE_PLANNING) {
            llvmLine = indeterminateLine(
                    "Emit LLVM IR",
                    stageDetail(stage, view.detail()),
                    width,
                    false);
        } else {
            llvmLine = indeterminateLine("Emit LLVM IR", "waiting", width, false);
        }
        return List.of(readLine, lowerLine, llvmLine);
    }

    private List<String> nativeLines(View view, int width, boolean completed) {
        ArrayList<String> lines = new ArrayList<>();
        appendNativePreparationLines(lines, view, width, completed);
        if (completed) {
            lines.add(completedNativeLine(view, width));
            return List.copyOf(lines);
        }
        BuildStage stage = view.stage();
        if (stage == BuildStage.NATIVE_BUILD && !view.nativeTargets().isEmpty()) {
            lines.addAll(activeNativeTargetLines(view, width));
            return List.copyOf(lines);
        }
        String state = nativeStageState(view);
        String buildDetail = stage == BuildStage.NATIVE_BUILD
                ? view.detail()
                : "waiting";
        lines.add(indeterminateLine("Build native", buildDetail, width, false));
        lines.add(stageLine(state, nativeStageDetail(view), width));
        return List.copyOf(lines);
    }

    private void appendNativePreparationLines(
            List<String> lines,
            View view,
            int width,
            boolean completedPhase) {
        for (NativePreparationStep step : NativePreparationStep.values()) {
            Work work = view.nativePreparationWork(step);
            if (completedPhase && !work.known()) {
                continue;
            }
            String label = nativePreparationLabel(step);
            if (!work.known()) {
                lines.add(indeterminateLine(label, "waiting", width, false));
            } else if (work.completed() >= work.total()) {
                lines.add(doneWorkLine(label, work, width, false));
            } else {
                lines.add(workLine(label, work, width, true));
            }
        }
    }

    private String nativePreparationLabel(NativePreparationStep step) {
        return switch (step) {
            case GENERATE_NATIVE_C -> "Generate C";
            case AUDIT_NATIVE_C -> "Audit native";
            case WRITE_NATIVE_IR -> "Write LLVM";
            case PREPARE_ZIG_BUILD -> "Prepare Zig";
        };
    }

    private String nativeStageState(View view) {
        if (view.stage() != BuildStage.NATIVE_BUILD) {
            return switch (view.stage()) {
                case INTERMEDIATE_WRITING -> "preparing";
                case TARGET_PREFLIGHT -> "checking";
                default -> "waiting";
            };
        }
        if (view.managedZigPreparation()) {
            return "preparing";
        }
        if (view.activeNativePreparation() == null) {
            return "preparing";
        }
        return switch (view.activeNativePreparation()) {
            case GENERATE_NATIVE_C -> "generating";
            case AUDIT_NATIVE_C -> "auditing";
            case WRITE_NATIVE_IR -> "writing";
            case PREPARE_ZIG_BUILD -> "preparing";
        };
    }

    private String nativeStageDetail(View view) {
        if (view.stage() != BuildStage.NATIVE_BUILD) {
            return stageDetail(view.stage(), view.detail());
        }
        if (view.managedZigPreparation()) {
            return "managed Zig toolchain";
        }
        if (view.activeNativePreparation() == null) {
            return view.detail();
        }
        if (!view.nativePreparationDetail().isBlank()) {
            return view.nativePreparationDetail();
        }
        return switch (view.activeNativePreparation()) {
            case GENERATE_NATIVE_C -> "native C source";
            case AUDIT_NATIVE_C -> "native hardening";
            case WRITE_NATIVE_IR -> "native LLVM modules";
            case PREPARE_ZIG_BUILD -> "Zig build graph";
        };
    }

    private List<String> activeNativeTargetLines(View view, int width) {
        long completed = view.nativeTargets().stream()
                .filter(NativeTargetProgress::completed)
                .count();
        long total = view.nativeTargets().size();
        if (completed == total) {
            return List.of(completedNativeLine(view, width));
        }
        ArrayList<String> lines = new ArrayList<>(view.nativeTargets().size() + 1);
        lines.add(progressLine(
                "Build native",
                completed,
                total,
                completed + "/" + total,
                "targets completed",
                width,
                completed == total,
                false));
        for (NativeTargetProgress target : view.nativeTargets()) {
            lines.add(targetProgressLine(target, width));
        }
        return List.copyOf(lines);
    }

    private String completedNativeLine(View view, int width) {
        if (view.nativeTargets().isEmpty()) {
            return doneLine("Build native", -1L, "done", width, false);
        }
        long targetCount = view.nativeTargets().size();
        return progressLine(
                "Build native",
                targetCount,
                targetCount,
                targetCount + "/" + targetCount,
                "targets completed",
                width,
                true,
                false);
    }

    private String targetProgressLine(
            NativeTargetProgress target,
            int width) {
        boolean compact = width < 48;
        String state = stateText(target.state(), compact);
        String percentage = percentageText(target);
        String suffix = state + "  " + percentage;
        int suffixWidth = TerminalText.displayWidth(suffix);
        int barWidth = Math.max(
                MIN_BAR_WIDTH,
                Math.min(
                        NORMAL_BAR_WIDTH,
                        width - LABEL_WIDTH - suffixWidth - 5));
        int labelWidth = Math.max(
                1,
                Math.min(
                        LABEL_WIDTH,
                        width - barWidth - suffixWidth - 5));
        String label = TerminalText.abbreviateHead(target.target(), labelWidth);
        String line = String.format(
                Locale.ROOT,
                "%-" + labelWidth + "s %s %s",
                label,
                progressBar(
                        target.completedUnits(),
                        target.totalUnits(),
                        barWidth,
                        target.completed()),
                suffix);
        return TerminalText.fitLine(line, width);
    }

    private String stateText(
            NativeTargetState state,
            boolean compact) {
        return switch (state) {
            case BUILDING -> compact ? "build" : "building";
            case LINKING -> compact ? "link" : "linking";
            case COMPLETED -> compact ? "done" : "completed";
        };
    }

    private String percentageText(NativeTargetProgress target) {
        int percentage = target.percentage();
        return percentage < 0 ? "--%" : percentage + "%";
    }

    private List<String> finalizeLines(View view, int width, boolean completed) {
        if (completed) {
            return List.of(workLine(
                    "Finalize JAR",
                    new Work(true, 3L, 3L, "done"),
                    width,
                    false));
        }
        int completedStages = Math.max(
                0,
                view.stage().ordinal() - BuildStage.JAR_PACKAGING.ordinal());
        Work work = new Work(
                true,
                completedStages,
                3L,
                finalizeDetail(view.stage(), view.detail()));
        return List.of(workLine("Finalize JAR", work, width, false));
    }

    private String workLine(String label, Work work, int width, boolean preserveDetailTail) {
        if (!work.known()) {
            return indeterminateLine(label, "waiting", width, preserveDetailTail);
        }
        boolean zeroWorkComplete = work.total() == 0L;
        long displayedCompleted = zeroWorkComplete
                ? 0L
                : Math.min(work.completed(), work.total());
        String count = displayedCompleted + "/" + work.total();
        String detail = work.detail().isBlank()
                ? zeroWorkComplete ? "no work" : "waiting"
                : work.detail();
        return progressLine(
                label,
                displayedCompleted,
                work.total(),
                count,
                detail,
                width,
                zeroWorkComplete,
                preserveDetailTail);
    }

    private String doneWorkLine(String label, Work work, int width, boolean preserveDetailTail) {
        if (!work.known()) {
            return doneLine(label, -1L, "done", width, preserveDetailTail);
        }
        String count = work.total() + "/" + work.total();
        return progressLine(
                label,
                work.total(),
                work.total(),
                count,
                "done",
                width,
                true,
                preserveDetailTail);
    }

    private String doneLine(
            String label,
            long total,
            String detail,
            int width,
            boolean preserveDetailTail) {
        String count = total >= 0L ? total + "/" + total : "done";
        return progressLine(
                label,
                Math.max(1L, total),
                Math.max(1L, total),
                count,
                total >= 0L ? detail : "",
                width,
                true,
                preserveDetailTail);
    }

    private String indeterminateLine(
            String label,
            String detail,
            int width,
            boolean preserveDetailTail) {
        return progressLine(
                label,
                0L,
                0L,
                "--",
                detail,
                width,
                false,
                preserveDetailTail);
    }

    private String progressLine(
            String label,
            long completed,
            long total,
            String count,
            String detail,
            int width,
            boolean forceComplete,
            boolean preserveDetailTail) {
        int barWidth = barWidth(width, count);
        String prefix = String.format(
                Locale.ROOT,
                "%-" + LABEL_WIDTH + "s %s %s  ",
                label,
                progressBar(completed, total, barWidth, forceComplete),
                count);
        int detailWidth = Math.max(0, width - TerminalText.displayWidth(prefix));
        String abbreviated = preserveDetailTail
                ? TerminalText.abbreviateTail(detail, detailWidth)
                : TerminalText.abbreviateHead(detail, detailWidth);
        return TerminalText.fitLine(prefix + abbreviated, width);
    }

    private String stageLine(String state, String detail, int width) {
        String prefix = String.format(
                Locale.ROOT,
                "%-" + LABEL_WIDTH + "s %-10s ",
                "Stage",
                state);
        int detailWidth = Math.max(0, width - TerminalText.displayWidth(prefix));
        return TerminalText.fitLine(
                prefix + TerminalText.abbreviateHead(detail, detailWidth),
                width);
    }

    private int barWidth(int terminalWidth, String count) {
        int available = terminalWidth
                - LABEL_WIDTH
                - 1
                - 2
                - 1
                - TerminalText.displayWidth(count)
                - 2
                - MIN_DETAIL_WIDTH;
        return Math.max(MIN_BAR_WIDTH, Math.min(NORMAL_BAR_WIDTH, available));
    }

    private String progressBar(
            long current,
            long total,
            int width,
            boolean forceComplete) {
        if (forceComplete) {
            return "[" + "=".repeat(width) + "]";
        }
        if (total <= 0L) {
            return "[" + "-".repeat(width) + "]";
        }
        long clamped = Math.max(0L, Math.min(current, total));
        int filled = (int) Math.min(width, clamped * width / total);
        if (filled <= 0) {
            return "[" + " ".repeat(width) + "]";
        }
        if (filled >= width) {
            return "[" + "=".repeat(width) + "]";
        }
        return "[" + "=".repeat(Math.max(0, filled - 1))
                + ">"
                + " ".repeat(width - filled)
                + "]";
    }

    private String stageDetail(BuildStage stage, String detail) {
        if (detail == null || detail.isBlank()) {
            return stage.displayName();
        }
        return stage.displayName() + " · " + detail;
    }

    private String finalizeDetail(BuildStage stage, String detail) {
        return switch (stage) {
            case JAR_PACKAGING -> detail.isBlank() ? "packing" : "packing " + detail;
            case ARTIFACT_AUDIT -> detail.isBlank() ? "auditing" : "auditing " + detail;
            case REPORT_WRITING -> "writing reports";
            default -> stageDetail(stage, detail);
        };
    }
}
