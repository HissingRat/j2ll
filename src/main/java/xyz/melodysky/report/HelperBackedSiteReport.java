package xyz.melodysky.report;

import java.util.Objects;

public record HelperBackedSiteReport(String helper, String reasonCode) {
    public HelperBackedSiteReport {
        Objects.requireNonNull(helper, "helper");
        Objects.requireNonNull(reasonCode, "reasonCode");
    }
}
