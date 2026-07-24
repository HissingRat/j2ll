package xyz.melodysky.analysis.runtime;

import java.util.Objects;

/** A method site retained through the configured JVM fallback boundary. */
public record FallbackBoundarySite(
        int instructionOffset,
        String target,
        String reasonCode,
        String fallbackMode) {
    public FallbackBoundarySite {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(reasonCode, "reasonCode");
        Objects.requireNonNull(fallbackMode, "fallbackMode");
    }
}
