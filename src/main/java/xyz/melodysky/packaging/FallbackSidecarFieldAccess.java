package xyz.melodysky.packaging;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import xyz.melodysky.analysis.field.FieldId;
import xyz.melodysky.analysis.field.FieldReferenceKind;
import xyz.melodysky.analysis.field.NativeFieldInternalizationPlan;
import xyz.melodysky.ir.model.NativeFieldSlotRef;

/**
 * Exact reference-field accesses that an encoded fallback helper must route
 * through the same JVM-managed sidecar as native LLVM code.
 */
public record FallbackSidecarFieldAccess(
        FieldId field,
        NativeFieldSlotRef slot,
        int readCount,
        int writeCount) implements Comparable<FallbackSidecarFieldAccess> {
    private static final String MARKER_PREFIX = "fallback-sidecar:v1:";
    private static final Base64.Encoder ENCODER =
            Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    public FallbackSidecarFieldAccess {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(slot, "slot");
        if (!slot.kind().reference()) {
            throw new IllegalArgumentException(
                    "fallback sidecar access requires a reference slot");
        }
        if (readCount < 0 || writeCount < 0 || readCount + writeCount == 0) {
            throw new IllegalArgumentException(
                    "fallback sidecar access requires at least one read or write");
        }
        if (xyz.melodysky.analysis.field.NativeFieldStorageKind
                .fromDescriptor(field.descriptor())
                .filter(kind -> kind.reference())
                .isEmpty()) {
            throw new IllegalArgumentException(
                    "fallback sidecar access requires a reference field");
        }
    }

    public String nativeSlotMarker() {
        return "native-slot:" + slot.encoded();
    }

    /**
     * Internal planning marker. The original identity is encoded only so it
     * can be carried between Java planning stages; it is never emitted as a C
     * string or native symbol.
     */
    public String marker() {
        return MARKER_PREFIX
                + encode(field.fieldKey())
                + ":"
                + encode(slot.encoded())
                + ":"
                + readCount
                + ":"
                + writeCount;
    }

    public static Optional<FallbackSidecarFieldAccess> parse(String marker) {
        if (marker == null || !marker.startsWith(MARKER_PREFIX)) {
            return Optional.empty();
        }
        String[] parts = marker.substring(MARKER_PREFIX.length()).split(":", 4);
        if (parts.length != 4) {
            return Optional.empty();
        }
        try {
            FieldId field = parseFieldKey(decode(parts[0]));
            NativeFieldSlotRef slot = NativeFieldSlotRef.parse(decode(parts[1]))
                    .orElseThrow();
            return Optional.of(new FallbackSidecarFieldAccess(
                    field,
                    slot,
                    Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3])));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    public static List<FallbackSidecarFieldAccess> parseMarkers(
            List<String> markers) {
        return markers.stream()
                .map(FallbackSidecarFieldAccess::parse)
                .flatMap(Optional::stream)
                .distinct()
                .sorted()
                .toList();
    }

    public static List<FallbackSidecarFieldAccess> forMethod(
            NativeFieldInternalizationPlan plan,
            String methodKey) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(methodKey, "methodKey");
        ArrayList<FallbackSidecarFieldAccess> accesses = new ArrayList<>();
        for (var decision : plan.internalizedFields()) {
            if (!plan.storageKind(decision).reference()) {
                continue;
            }
            int reads = 0;
            int writes = 0;
            for (var access : decision.accesses()) {
                if (!access.methodKey().equals(methodKey)) {
                    continue;
                }
                if (access.referenceKind()
                        == FieldReferenceKind.BYTECODE_STATIC_READ) {
                    reads++;
                } else if (access.referenceKind()
                        == FieldReferenceKind.BYTECODE_STATIC_WRITE) {
                    writes++;
                }
            }
            if (reads + writes == 0) {
                continue;
            }
            accesses.add(new FallbackSidecarFieldAccess(
                    decision.field(),
                    new NativeFieldSlotRef(
                            plan.storageKind(decision),
                            decision.nativeSlotId().orElseThrow(),
                            plan.referenceIndex(decision)),
                    reads,
                    writes));
        }
        accesses.sort(Comparator.naturalOrder());
        return List.copyOf(accesses);
    }

    @Override
    public int compareTo(FallbackSidecarFieldAccess other) {
        return field.compareTo(other.field);
    }

    private static String encode(String value) {
        return ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(DECODER.decode(value), StandardCharsets.UTF_8);
    }

    private static FieldId parseFieldKey(String fieldKey) {
        int ownerEnd = fieldKey.indexOf('#');
        int descriptorStart = fieldKey.indexOf('!', ownerEnd + 1);
        if (ownerEnd <= 0
                || descriptorStart <= ownerEnd + 1
                || descriptorStart + 1 >= fieldKey.length()) {
            throw new IllegalArgumentException("invalid field key");
        }
        return new FieldId(
                fieldKey.substring(0, ownerEnd),
                fieldKey.substring(ownerEnd + 1, descriptorStart),
                fieldKey.substring(descriptorStart + 1));
    }
}
