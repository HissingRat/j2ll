package xyz.melodysky.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.melodysky.progress.BuildProgressListener;
import xyz.melodysky.progress.BuildStage;
import xyz.melodysky.progress.NativeTargetProgress;
import xyz.melodysky.toolchain.NativeBuildPlan;
import xyz.melodysky.toolchain.NativeBuildUnit;
import xyz.melodysky.toolchain.NativeTargetBuildState;
import xyz.melodysky.toolchain.TargetTriple;

class MainlineProgressTest {
    @Test
    void nativeMatrixPublishesStableTargetRowsWithoutConcatenatingNames() {
        RecordingListener listener = new RecordingListener();
        MainlineProgress progress = new MainlineProgress(listener);
        NativeBuildPlan plan = new NativeBuildPlan(List.of(
                new NativeBuildUnit(TargetTriple.WINDOWS_X64, Path.of("windows.dll"), "sample"),
                new NativeBuildUnit(TargetTriple.LINUX_ARM64, Path.of("linux.so"), "sample")));

        progress.targetPreflight(2);
        progress.nativeBuild(plan, true);

        assertEquals(List.of(
                "TARGET_PREFLIGHT:2 selected targets",
                "NATIVE_BUILD:2 targets"), listener.started);
        assertEquals(List.of(), listener.nativeTargets);
        assertEquals(List.of(), listener.progressed);

        progress.nativeBuildProgress().buildStarted(
                List.of(TargetTriple.LINUX_ARM64, TargetTriple.WINDOWS_X64));
        assertEquals(List.of("linux-arm64", "windows-x64"), listener.nativeTargets);
        progress.nativeBuildProgress().targetProgress(
                new xyz.melodysky.toolchain.NativeTargetProgress(
                        TargetTriple.WINDOWS_X64,
                        NativeTargetBuildState.BUILDING,
                        1,
                        3),
                0,
                2);
        progress.nativeBuildProgress().targetProgress(
                new xyz.melodysky.toolchain.NativeTargetProgress(
                        TargetTriple.WINDOWS_X64,
                        NativeTargetBuildState.LINKING,
                        2,
                        3),
                0,
                2);
        progress.nativeBuildProgress().targetProgress(
                new xyz.melodysky.toolchain.NativeTargetProgress(
                        TargetTriple.WINDOWS_X64,
                        NativeTargetBuildState.COMPLETED,
                        3,
                        3),
                1,
                2);
        progress.nativeBuildProgress().targetProgress(
                new xyz.melodysky.toolchain.NativeTargetProgress(
                        TargetTriple.LINUX_ARM64,
                        NativeTargetBuildState.COMPLETED,
                        4,
                        4),
                2,
                2);

        assertEquals(List.of(
                "windows-x64:BUILDING:1/3",
                "windows-x64:LINKING:2/3",
                "windows-x64:COMPLETED:3/3",
                "linux-arm64:COMPLETED:4/4"), listener.nativeProgress);
    }

    @Test
    void methodAndClassWorkExposeRealCurrentTotalCounts() {
        RecordingListener listener = new RecordingListener();
        MainlineProgress progress = new MainlineProgress(listener);

        progress.methodLowering(3);
        progress.methodLoweringProgress(2, 3, "pkg/Foo#run!()V");
        progress.llvmEmission(2);
        progress.llvmEmissionProgress(1, 2, "pkg/Foo");

        assertEquals(List.of(
                "METHOD_LOWERING:3 methods",
                "LLVM_EMISSION:2 classes"), listener.started);
        assertEquals(List.of(
                "METHOD_LOWERING:0/3:waiting",
                "METHOD_LOWERING:1/3:pkg/Foo#run!()V",
                "LLVM_EMISSION:0/2:waiting",
                "LLVM_EMISSION:0/2:pkg/Foo"), listener.progressed);
    }

    @Test
    void zeroMethodAndClassCountsRemainZero() {
        RecordingListener listener = new RecordingListener();
        MainlineProgress progress = new MainlineProgress(listener);

        progress.methodLowering(0);
        progress.methodLoweringComplete(0);
        progress.llvmEmission(0);
        progress.llvmEmissionComplete(0);

        assertEquals(List.of(
                "METHOD_LOWERING:0/0:no methods selected",
                "METHOD_LOWERING:0/0:no methods selected",
                "LLVM_EMISSION:0/0:no LLVM classes",
                "LLVM_EMISSION:0/0:no LLVM classes"), listener.progressed);
    }

    private static final class RecordingListener implements BuildProgressListener {
        private final ArrayList<String> started = new ArrayList<>();
        private final ArrayList<String> progressed = new ArrayList<>();
        private final ArrayList<String> nativeTargets = new ArrayList<>();
        private final ArrayList<String> nativeProgress = new ArrayList<>();

        @Override
        public void stageStarted(BuildStage stage, String detail) {
            started.add(stage.name() + ":" + detail);
        }

        @Override
        public void stageProgress(BuildStage stage, long completed, long total, String detail) {
            progressed.add(stage.name() + ":" + completed + "/" + total + ":" + detail);
        }

        @Override
        public void nativeTargetsStarted(List<String> targets) {
            nativeTargets.addAll(targets);
        }

        @Override
        public void nativeTargetProgress(NativeTargetProgress progress) {
            nativeProgress.add(progress.target()
                    + ":" + progress.state()
                    + ":" + progress.completedUnits()
                    + "/" + progress.totalUnits());
        }

        @Override
        public void finished(boolean successful) {
        }
    }
}
