package xyz.melodysky.protection.audit;

import java.util.Objects;

/**
 * Explicit wrapper call-graph evidence produced by Ghidra, another binary
 * analyzer, or the generated native plan.
 *
 * <p>The resolution fingerprint must be normalized by the producer. This
 * class never parses disassembly text and never treats a regex match as final
 * binary evidence.
 */
public record WrapperCallEvidence(
        String bindingIdentityHash,
        WrapperCallShape shape,
        String resolutionFingerprintHash,
        WrapperEvidenceKind evidenceKind)
        implements Comparable<WrapperCallEvidence> {
    public WrapperCallEvidence {
        bindingIdentityHash = HashOnlyEvidence.requireSha256(
                bindingIdentityHash,
                "bindingIdentityHash");
        Objects.requireNonNull(shape, "shape");
        Objects.requireNonNull(evidenceKind, "evidenceKind");
        if (shape == WrapperCallShape.UNRESOLVED) {
            if (resolutionFingerprintHash != null) {
                throw new IllegalArgumentException(
                        "unresolved wrapper evidence must not claim a resolution fingerprint");
            }
        } else {
            resolutionFingerprintHash = HashOnlyEvidence.requireSha256(
                    resolutionFingerprintHash,
                    "resolutionFingerprintHash");
        }
    }

    @Override
    public int compareTo(WrapperCallEvidence other) {
        return bindingIdentityHash.compareTo(other.bindingIdentityHash);
    }
}
