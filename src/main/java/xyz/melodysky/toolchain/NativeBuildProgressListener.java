package xyz.melodysky.toolchain;

import java.util.List;
import xyz.melodysky.progress.NativePreparationProgress;

@FunctionalInterface
public interface NativeBuildProgressListener {
    void targetCompleted(TargetTriple target, int completedTargets, int totalTargets);

    default void buildStarted(List<TargetTriple> targets) {
    }

    /** Managed Zig resolution is a transient status, not a counted work row. */
    default void managedZigPreparationStarted() {
    }

    /** Reports real native workspace preparation work before Zig starts. */
    default void preparationProgress(NativePreparationProgress progress) {
    }

    default void targetProgress(
            NativeTargetProgress progress,
            int completedTargets,
            int totalTargets) {
        if (progress.state() == NativeTargetBuildState.COMPLETED) {
            targetCompleted(progress.target(), completedTargets, totalTargets);
        }
    }

    static NativeBuildProgressListener none() {
        return NoopNativeBuildProgressListener.INSTANCE;
    }

    enum NoopNativeBuildProgressListener implements NativeBuildProgressListener {
        INSTANCE;

        @Override
        public void targetCompleted(TargetTriple target, int completedTargets, int totalTargets) {
        }
    }
}
