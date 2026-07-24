package xyz.melodysky.toolchain;

import java.util.List;

@FunctionalInterface
public interface NativeBuildProgressListener {
    void targetCompleted(TargetTriple target, int completedTargets, int totalTargets);

    default void buildStarted(List<TargetTriple> targets) {
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
