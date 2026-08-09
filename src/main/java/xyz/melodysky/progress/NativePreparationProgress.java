package xyz.melodysky.progress;

import java.util.Objects;

/** A real completed/total work count for one native preparation step. */
public record NativePreparationProgress(
        NativePreparationStep step,
        long completed,
        long total,
        String detail) {
    public NativePreparationProgress {
        Objects.requireNonNull(step, "step");
        if (completed < 0L || total < 0L || completed > total) {
            throw new IllegalArgumentException(
                    "native preparation progress must satisfy 0 <= completed <= total");
        }
        detail = detail == null ? "" : detail;
    }
}
