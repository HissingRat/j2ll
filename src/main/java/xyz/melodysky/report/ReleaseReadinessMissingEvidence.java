package xyz.melodysky.report;

public record ReleaseReadinessMissingEvidence(
        String type,
        String name,
        String reasonCode,
        String detail,
        String reportPath) {
}
