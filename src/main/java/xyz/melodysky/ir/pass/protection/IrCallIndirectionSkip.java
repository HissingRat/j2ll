package xyz.melodysky.ir.pass.protection;

import java.util.Objects;

public record IrCallIndirectionSkip(IrCallSiteId siteId, String reasonCode)
        implements Comparable<IrCallIndirectionSkip> {
    public IrCallIndirectionSkip {
        Objects.requireNonNull(siteId, "siteId");
        Objects.requireNonNull(reasonCode, "reasonCode");
        if (reasonCode.isBlank()) {
            throw new IllegalArgumentException("reasonCode must not be blank");
        }
    }

    @Override
    public int compareTo(IrCallIndirectionSkip other) {
        int bySite = siteId.compareTo(other.siteId);
        return bySite != 0 ? bySite : reasonCode.compareTo(other.reasonCode);
    }
}
