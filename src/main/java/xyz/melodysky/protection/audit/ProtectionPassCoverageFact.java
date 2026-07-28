package xyz.melodysky.protection.audit;

import java.util.Objects;

/**
 * One pass/subject evaluation supplied by the protection stage.
 *
 * <p>The subject is a context-independent hash of the logical method/module
 * identity so the same subject can be compared across builds without writing
 * its plaintext identity.
 */
public record ProtectionPassCoverageFact(
        String layer,
        String passName,
        String subjectIdentityHash,
        boolean requested,
        ProtectionApplicability applicability,
        boolean affected,
        String status,
        String reasonCode)
        implements Comparable<ProtectionPassCoverageFact> {
    public ProtectionPassCoverageFact {
        Objects.requireNonNull(layer, "layer");
        Objects.requireNonNull(passName, "passName");
        subjectIdentityHash = HashOnlyEvidence.requireSha256(
                subjectIdentityHash,
                "subjectIdentityHash");
        Objects.requireNonNull(applicability, "applicability");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(reasonCode, "reasonCode");
        if (layer.isBlank()
                || passName.isBlank()
                || status.isBlank()
                || reasonCode.isBlank()) {
            throw new IllegalArgumentException(
                    "protection pass coverage strings must not be blank");
        }
        if (affected
                && (!requested
                        || applicability != ProtectionApplicability.APPLICABLE)) {
            throw new IllegalArgumentException(
                    "affected pass evidence must be requested and applicable");
        }
    }

    public String passKey() {
        return layer + "\0" + passName;
    }

    public String factKey() {
        return passKey() + "\0" + subjectIdentityHash;
    }

    @Override
    public int compareTo(ProtectionPassCoverageFact other) {
        int byLayer = layer.compareTo(other.layer);
        if (byLayer != 0) {
            return byLayer;
        }
        int byPass = passName.compareTo(other.passName);
        if (byPass != 0) {
            return byPass;
        }
        return subjectIdentityHash.compareTo(other.subjectIdentityHash);
    }
}
