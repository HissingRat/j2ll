package xyz.melodysky.report;

import java.util.Objects;

public record KnownBlockerEntry(
        String id,
        String reasonCode,
        String severity,
        String targetMilestone,
        String currentBehavior,
        String reportLocation,
        String suggestedFuturePath) {
    public KnownBlockerEntry(
            String id,
            String reasonCode,
            String currentBehavior,
            String reportLocation,
            String suggestedFuturePath) {
        this(id, reasonCode, "future-blocker", "post-rc", currentBehavior, reportLocation, suggestedFuturePath);
    }

    public KnownBlockerEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(reasonCode, "reasonCode");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(targetMilestone, "targetMilestone");
        Objects.requireNonNull(currentBehavior, "currentBehavior");
        Objects.requireNonNull(reportLocation, "reportLocation");
        Objects.requireNonNull(suggestedFuturePath, "suggestedFuturePath");
    }
}
