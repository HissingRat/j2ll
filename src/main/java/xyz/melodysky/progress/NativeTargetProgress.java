package xyz.melodysky.progress;

import java.util.Objects;

public record NativeTargetProgress(
        String target,
        NativeTargetState state,
        long completedUnits,
        long totalUnits) {
    public NativeTargetProgress {
        target = Objects.requireNonNull(target, "target");
        state = Objects.requireNonNull(state, "state");
        completedUnits = Math.max(0L, completedUnits);
        totalUnits = Math.max(0L, totalUnits);
        if (totalUnits > 0L) {
            completedUnits = Math.min(completedUnits, totalUnits);
        }
    }

    public static NativeTargetProgress building(String target) {
        return new NativeTargetProgress(target, NativeTargetState.BUILDING, 0L, 0L);
    }

    public boolean hasKnownUnits() {
        return totalUnits > 0L;
    }

    public boolean completed() {
        return state == NativeTargetState.COMPLETED;
    }

    public int percentage() {
        if (completed()) {
            return 100;
        }
        if (!hasKnownUnits()) {
            return -1;
        }
        return (int) Math.min(
                100.0d,
                Math.floor((completedUnits * 100.0d) / totalUnits));
    }

    public NativeTargetProgress withTarget(String normalizedTarget) {
        return new NativeTargetProgress(
                normalizedTarget,
                state,
                completedUnits,
                totalUnits);
    }
}
