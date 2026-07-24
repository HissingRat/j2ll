package xyz.melodysky.analysis.runtime;

import java.util.Objects;

/** A helper-backed lowering fact, independent of its JSON representation. */
public record RuntimeHelperSite(String helper, String reasonCode) {
    public RuntimeHelperSite {
        Objects.requireNonNull(helper, "helper");
        Objects.requireNonNull(reasonCode, "reasonCode");
    }
}
