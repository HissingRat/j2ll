package xyz.melodysky.report;

import java.util.Objects;

public record FallbackSiteReport(
        int instructionOffset,
        String target,
        String reasonCode,
        String fallbackMode) {
    public FallbackSiteReport {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(reasonCode, "reasonCode");
        Objects.requireNonNull(fallbackMode, "fallbackMode");
    }
}
