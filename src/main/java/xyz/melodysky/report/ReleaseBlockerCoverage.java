package xyz.melodysky.report;

import java.util.Objects;

public record ReleaseBlockerCoverage(
        String blockerId,
        String reasonCode,
        String reportLocation,
        boolean covered,
        String evidenceType,
        String caseName,
        String expectedStatus) {
    public ReleaseBlockerCoverage {
        Objects.requireNonNull(blockerId, "blockerId");
        Objects.requireNonNull(reasonCode, "reasonCode");
        Objects.requireNonNull(reportLocation, "reportLocation");
        Objects.requireNonNull(evidenceType, "evidenceType");
        Objects.requireNonNull(expectedStatus, "expectedStatus");
    }
}
