package xyz.melodysky.toolchain;

import java.util.List;

@FunctionalInterface
public interface NativeBuildProgressListener {
    void targetCompleted(TargetTriple target, int completedTargets, int totalTargets);

    default void buildStarted(List<TargetTriple> targets) {
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
