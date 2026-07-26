package xyz.melodysky.toolchain;

import java.util.Objects;

public record NativeTargetProgress(
        TargetTriple target,
        NativeTargetBuildState state,
        int completedUnits,
        int totalUnits) {
    public NativeTargetProgress {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(state, "state");
        if (totalUnits <= 0) {
            throw new IllegalArgumentException("totalUnits must be positive");
        }
        if (completedUnits < 0 || completedUnits > totalUnits) {
            throw new IllegalArgumentException("completedUnits must be between zero and totalUnits");
        }
        if (state == NativeTargetBuildState.COMPLETED && completedUnits != totalUnits) {
            throw new IllegalArgumentException("completed target must have all work units complete");
        }
        if (state != NativeTargetBuildState.COMPLETED && completedUnits == totalUnits) {
            throw new IllegalArgumentException("active target must have unfinished work");
        }
        if (state == NativeTargetBuildState.LINKING && completedUnits != totalUnits - 1) {
            throw new IllegalArgumentException(
                    "linking target must have completed every compile work unit");
        }
    }

    public int percentage() {
        return (int) ((long) completedUnits * 100L / totalUnits);
    }
}
