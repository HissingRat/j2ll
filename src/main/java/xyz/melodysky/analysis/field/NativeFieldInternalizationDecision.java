package xyz.melodysky.analysis.field;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record NativeFieldInternalizationDecision(
        FieldId field,
        FieldInternalizationStatus status,
        NativeFieldInternalizationStorage storage,
        Optional<String> nativeSlotId,
        Optional<NativeFieldConstant> constant,
        List<FieldAccessSite> accesses,
        List<FieldInternalizationReason> reasons) implements Comparable<NativeFieldInternalizationDecision> {
    /** Compatibility constructor for mutable-slot and kept-field fixtures. */
    public NativeFieldInternalizationDecision(
            FieldId field,
            FieldInternalizationStatus status,
            Optional<String> nativeSlotId,
            List<FieldAccessSite> accesses,
            List<FieldInternalizationReason> reasons) {
        this(
                field,
                status,
                status == FieldInternalizationStatus.INTERNALIZED
                        ? NativeFieldInternalizationStorage.NATIVE_SLOT
                        : NativeFieldInternalizationStorage.JVM_FIELD,
                nativeSlotId,
                Optional.empty(),
                accesses,
                reasons);
    }

    public NativeFieldInternalizationDecision {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(storage, "storage");
        Objects.requireNonNull(nativeSlotId, "nativeSlotId");
        Objects.requireNonNull(constant, "constant");
        accesses = accesses.stream().filter(Objects::nonNull).sorted().toList();
        reasons = reasons.stream().filter(Objects::nonNull).distinct().sorted().toList();
        if (reasons.isEmpty()) {
            throw new IllegalArgumentException("a field internalization decision requires a reason");
        }
        if (status == FieldInternalizationStatus.KEPT) {
            if (storage != NativeFieldInternalizationStorage.JVM_FIELD
                    || nativeSlotId.isPresent()
                    || constant.isPresent()) {
                throw new IllegalArgumentException(
                        "kept field must retain JVM storage without native payload");
            }
        } else if (storage == NativeFieldInternalizationStorage.NATIVE_SLOT) {
            if (nativeSlotId.isEmpty() || constant.isPresent()) {
                throw new IllegalArgumentException(
                        "native-slot field requires only a native slot id");
            }
            if (!reasons.equals(List.of(
                    FieldInternalizationReason.FIELD_INTERNALIZATION_ELIGIBLE))) {
                throw new IllegalArgumentException(
                        "native-slot field must have only the mutable eligible reason");
            }
        } else if (storage == NativeFieldInternalizationStorage.COMPILE_TIME_CONSTANT) {
            if (nativeSlotId.isPresent()
                    || constant.isEmpty()
                    || !constant.orElseThrow().descriptor().equals(field.descriptor())) {
                throw new IllegalArgumentException(
                        "constant-folded field requires only a matching constant payload");
            }
            if (!reasons.equals(List.of(
                    FieldInternalizationReason.FIELD_CONSTANT_INTERNALIZATION_ELIGIBLE))) {
                throw new IllegalArgumentException(
                        "constant-folded field must have only the constant eligible reason");
            }
        } else {
            throw new IllegalArgumentException(
                    "internalized field cannot retain JVM field storage");
        }
    }

    public boolean internalized() {
        return status == FieldInternalizationStatus.INTERNALIZED;
    }

    public boolean nativeStored() {
        return internalized()
                && storage == NativeFieldInternalizationStorage.NATIVE_SLOT;
    }

    public boolean constantFolded() {
        return internalized()
                && storage == NativeFieldInternalizationStorage.COMPILE_TIME_CONSTANT;
    }

    public FieldInternalizationReason primaryReason() {
        return reasons.get(0);
    }

    @Override
    public int compareTo(NativeFieldInternalizationDecision other) {
        return field.compareTo(other.field);
    }
}
