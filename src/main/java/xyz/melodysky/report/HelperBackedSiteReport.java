package xyz.melodysky.report;

import java.util.Objects;

/**
 * Privacy-preserving helper-backed lowering evidence.
 *
 * <p>The full compiler helper identity may contain owner/member descriptors or
 * business string carriers. Reports retain only a non-sensitive helper kind
 * and a domain-separated hash of the complete identity.</p>
 */
public record HelperBackedSiteReport(
        String helperKind,
        String helperIdentityHash,
        String reasonCode) {
    public HelperBackedSiteReport {
        Objects.requireNonNull(helperKind, "helperKind");
        Objects.requireNonNull(helperIdentityHash, "helperIdentityHash");
        Objects.requireNonNull(reasonCode, "reasonCode");
        if (helperKind.isBlank()) {
            throw new IllegalArgumentException("helper kind must not be blank");
        }
        if (!helperIdentityHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "helper identity hash must be a lower-case SHA-256");
        }
    }
}
