package xyz.melodysky.protection.audit;

import java.util.Objects;

/** Stable, non-sensitive failure raised while reading debug LLVM evidence. */
public final class BusinessStringCarrierScanException
        extends IllegalArgumentException {
    private final String reasonCode;

    BusinessStringCarrierScanException(String reasonCode) {
        super(Objects.requireNonNull(reasonCode, "reasonCode"));
        if (reasonCode.isBlank()) {
            throw new IllegalArgumentException(
                    "carrier scan reason code must not be blank");
        }
        this.reasonCode = reasonCode;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
