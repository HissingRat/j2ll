package xyz.melodysky.ir.model;

import java.util.Objects;
import java.util.Optional;
import xyz.melodysky.analysis.field.NativeFieldStorageKind;

/**
 * Versioned, opaque IR reference to internalized field storage.
 *
 * <p>No owner, Java field name, or descriptor is carried into generated LLVM
 * or C. The exact storage kind is retained because the SSA {@code I32} type
 * alone cannot distinguish JVM narrow-field semantics.</p>
 */
public record NativeFieldSlotRef(
        NativeFieldStorageKind kind,
        String opaqueSlotId,
        int referenceIndex) {
    private static final String PREFIX = "j2ll:nfs:v1:";

    public NativeFieldSlotRef {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(opaqueSlotId, "opaqueSlotId");
        if (opaqueSlotId.isBlank() || opaqueSlotId.indexOf(':') >= 0) {
            throw new IllegalArgumentException("native field slot id must be non-blank and colon-free");
        }
        if ((kind.reference() && referenceIndex < 0)
                || (!kind.reference() && referenceIndex != -1)) {
            throw new IllegalArgumentException(
                    "reference slots require a non-negative index and primitive slots require -1");
        }
    }

    public static NativeFieldSlotRef create(
            String descriptor,
            String opaqueSlotId,
            int referenceIndex) {
        NativeFieldStorageKind kind = NativeFieldStorageKind.fromDescriptor(descriptor)
                .orElseThrow(() -> new IllegalArgumentException(
                        "unsupported internalized field descriptor: " + descriptor));
        return new NativeFieldSlotRef(
                kind,
                opaqueSlotId,
                kind.reference() ? referenceIndex : -1);
    }

    public String encoded() {
        return PREFIX
                + kind.wireName()
                + ":"
                + (kind.reference() ? Integer.toString(referenceIndex) : "-")
                + ":"
                + opaqueSlotId;
    }

    public static Optional<NativeFieldSlotRef> parse(String encoded) {
        if (encoded == null || !encoded.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String[] parts = encoded.split(":", 6);
        if (parts.length != 6
                || !parts[0].equals("j2ll")
                || !parts[1].equals("nfs")
                || !parts[2].equals("v1")) {
            return Optional.empty();
        }
        Optional<NativeFieldStorageKind> kind = NativeFieldStorageKind.fromWireName(parts[3]);
        if (kind.isEmpty()) {
            return Optional.empty();
        }
        try {
            int referenceIndex = parts[4].equals("-") ? -1 : Integer.parseInt(parts[4]);
            return Optional.of(new NativeFieldSlotRef(kind.orElseThrow(), parts[5], referenceIndex));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
