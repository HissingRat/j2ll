package xyz.melodysky.progress;

import java.util.List;

public interface BuildProgressListener {
    void stageStarted(BuildStage stage, String detail);

    default void stageStarted(BuildStage stage) {
        stageStarted(stage, "");
    }

    void stageProgress(BuildStage stage, long completed, long total, String detail);

    default void nativeTargetsStarted(List<String> targets) {
    }

    default void nativeTargetProgress(NativeTargetProgress progress) {
        if (progress != null && progress.completed()) {
            nativeTargetCompleted(progress.target());
        }
    }

    /**
     * Compatibility callback for listeners that only need target completion.
     */
    default void nativeTargetCompleted(String target) {
    }

    /** Clears or suspends an active terminal region before reading user input. */
    default void beforeUserInput() {
    }

    void finished(boolean successful);

    static BuildProgressListener none() {
        return NoopBuildProgressListener.INSTANCE;
    }

    enum NoopBuildProgressListener implements BuildProgressListener {
        INSTANCE;

        @Override
        public void stageStarted(BuildStage stage, String detail) {
        }

        @Override
        public void stageProgress(BuildStage stage, long completed, long total, String detail) {
        }

        @Override
        public void finished(boolean successful) {
        }
    }
}
