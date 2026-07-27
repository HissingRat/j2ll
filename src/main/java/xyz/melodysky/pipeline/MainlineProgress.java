package xyz.melodysky.pipeline;

import java.nio.file.Path;
import java.util.List;
import xyz.melodysky.progress.BuildProgressListener;
import xyz.melodysky.progress.BuildStage;
import xyz.melodysky.progress.NativeTargetProgress;
import xyz.melodysky.progress.NativeTargetState;
import xyz.melodysky.toolchain.NativeBuildPlan;
import xyz.melodysky.toolchain.NativeBuildProgressListener;
import xyz.melodysky.toolchain.NativeLlvmCompilationListener;
import xyz.melodysky.toolchain.TargetTriple;

final class MainlineProgress {
    private final BuildProgressListener listener;

    MainlineProgress(BuildProgressListener listener) {
        this.listener = java.util.Objects.requireNonNull(listener, "listener");
    }

    void inputInspection(Path inputJar) {
        start(BuildStage.INPUT_INSPECTION, inputJar.getFileName().toString());
    }

    void classParsing(Path inputJar) {
        start(BuildStage.CLASS_PARSING, inputJar.getFileName().toString());
    }

    void methodSelection(int classCount) {
        start(BuildStage.METHOD_SELECTION, classCount + " classes");
    }

    void methodsSelected(int selectedCount) {
        listener.stageProgress(
                BuildStage.METHOD_SELECTION,
                selectedCount,
                Math.max(1, selectedCount),
                selectedCount + " methods selected");
    }

    void programAnalysis(int classCount) {
        start(BuildStage.PROGRAM_ANALYSIS, classCount + " classes");
    }

    void methodLowering(int methodCount) {
        start(BuildStage.METHOD_LOWERING, methodCount + " methods");
        listener.stageProgress(
                BuildStage.METHOD_LOWERING,
                0,
                methodCount,
                methodCount == 0 ? "no methods selected" : "waiting");
    }

    void methodLoweringProgress(int current, int total, String methodKey) {
        listener.stageProgress(
                BuildStage.METHOD_LOWERING,
                Math.max(0, current - 1),
                Math.max(0, total),
                methodKey);
    }

    void methodLoweringComplete(int total) {
        listener.stageProgress(
                BuildStage.METHOD_LOWERING,
                total,
                Math.max(0, total),
                total == 0 ? "no methods selected" : "done");
    }

    void nativePlanning(int loweredMethodCount) {
        start(BuildStage.NATIVE_PLANNING, loweredMethodCount + " lowered methods");
    }

    void llvmEmission(int classCount) {
        start(BuildStage.LLVM_EMISSION, classCount + " classes");
        listener.stageProgress(
                BuildStage.LLVM_EMISSION,
                0,
                classCount,
                classCount == 0 ? "no LLVM classes" : "waiting");
    }

    void llvmEmissionProgress(int current, int total, String className) {
        listener.stageProgress(
                BuildStage.LLVM_EMISSION,
                Math.max(0, current - 1),
                Math.max(0, total),
                className);
    }

    void llvmEmissionComplete(int total) {
        listener.stageProgress(
                BuildStage.LLVM_EMISSION,
                total,
                Math.max(0, total),
                total == 0 ? "no LLVM classes" : "done");
    }

    NativeLlvmCompilationListener llvmCompilationProgress() {
        return new NativeLlvmCompilationListener() {
            @Override
            public void started(int totalOwners) {
                llvmEmission(totalOwners);
            }

            @Override
            public void moduleStarted(
                    int currentOwner,
                    int totalOwners,
                    String owner) {
                llvmEmissionProgress(currentOwner, totalOwners, owner);
            }

            @Override
            public void completed(int totalOwners) {
                llvmEmissionComplete(totalOwners);
            }
        };
    }

    void intermediateWriting(boolean enabled) {
        start(BuildStage.INTERMEDIATE_WRITING, enabled ? "enabled" : "manifest only");
    }

    void targetPreflight(int targetCount) {
        start(BuildStage.TARGET_PREFLIGHT, targetCount + " selected targets");
    }

    void nativeBuild(NativeBuildPlan plan, boolean hasNativeImplementations) {
        if (!hasNativeImplementations) {
            start(BuildStage.NATIVE_BUILD, "no native implementations");
            return;
        }
        List<String> targets = plan.units().stream()
                .map(unit -> unit.target().directoryName())
                .toList();
        start(
                BuildStage.NATIVE_BUILD,
                targets.isEmpty() ? "no buildable targets" : targetCount(targets.size()));
    }

    void beforeUserInput() {
        listener.beforeUserInput();
    }

    NativeBuildProgressListener nativeBuildProgress() {
        return new NativeBuildProgressListener() {
            @Override
            public void buildStarted(List<TargetTriple> targets) {
                listener.nativeTargetsStarted(targets.stream()
                        .map(TargetTriple::directoryName)
                        .toList());
            }

            @Override
            public void targetProgress(
                    xyz.melodysky.toolchain.NativeTargetProgress progress,
                    int completedTargets,
                    int totalTargets) {
                listener.nativeTargetProgress(new NativeTargetProgress(
                        progress.target().directoryName(),
                        switch (progress.state()) {
                            case BUILDING -> NativeTargetState.BUILDING;
                            case LINKING -> NativeTargetState.LINKING;
                            case COMPLETED -> NativeTargetState.COMPLETED;
                        },
                        progress.completedUnits(),
                        progress.totalUnits()));
            }

            @Override
            public void targetCompleted(
                    TargetTriple target,
                    int completedTargets,
                    int totalTargets) {
                listener.nativeTargetProgress(new NativeTargetProgress(
                        target.directoryName(),
                        NativeTargetState.COMPLETED,
                        0L,
                        0L));
            }
        };
    }

    void jarPackaging(Path outputJar, boolean skipped) {
        start(
                BuildStage.JAR_PACKAGING,
                skipped ? "skipped after earlier failure" : outputJar.getFileName().toString());
    }

    void artifactAudit(Path outputJar, boolean skipped) {
        start(
                BuildStage.ARTIFACT_AUDIT,
                skipped ? "skipped after earlier failure" : outputJar.getFileName().toString());
    }

    void reportWriting() {
        start(BuildStage.REPORT_WRITING, "reports");
    }

    private void start(BuildStage stage, String detail) {
        listener.stageStarted(stage, detail);
    }

    private String targetCount(int count) {
        return count + (count == 1 ? " target" : " targets");
    }
}
