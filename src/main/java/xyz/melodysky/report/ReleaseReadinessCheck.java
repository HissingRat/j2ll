package xyz.melodysky.report;

import java.util.Objects;

public record ReleaseReadinessCheck(
        String name,
        String status,
        String reasonCode,
        String detail) {
    public ReleaseReadinessCheck {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(reasonCode, "reasonCode");
        Objects.requireNonNull(detail, "detail");
    }

    public static ReleaseReadinessCheck passed(String name, String reasonCode, String detail) {
        return new ReleaseReadinessCheck(name, "passed", reasonCode, detail);
    }

    public static ReleaseReadinessCheck failed(String name, String reasonCode, String detail) {
        return new ReleaseReadinessCheck(name, "failed", reasonCode, detail);
    }
}
