package xyz.melodysky.report;

import java.util.Objects;

public record ArtifactAuditCheck(
        String name,
        String status,
        String reasonCode,
        String message) {
    public ArtifactAuditCheck {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(reasonCode, "reasonCode");
        Objects.requireNonNull(message, "message");
    }

    public static ArtifactAuditCheck passed(String name, String reasonCode, String message) {
        return new ArtifactAuditCheck(name, "passed", reasonCode, message);
    }

    public static ArtifactAuditCheck failed(String name, String reasonCode, String message) {
        return new ArtifactAuditCheck(name, "failed", reasonCode, message);
    }

    public static ArtifactAuditCheck skipped(String name, String reasonCode, String message) {
        return new ArtifactAuditCheck(name, "skipped", reasonCode, message);
    }
}
