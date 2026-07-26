package xyz.melodysky.analysis.field;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record NativeFieldInternalizationDecision(
        FieldId field,
        FieldInternalizationStatus status,
        Optional<String> nativeSlotId,
        List<FieldAccessSite> accesses,
        List<FieldInternalizationReason> reasons) implements Comparable<NativeFieldInternalizationDecision> {
    public NativeFieldInternalizationDecision {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(nativeSlotId, "nativeSlotId");
        accesses = accesses.stream().filter(Objects::nonNull).sorted().toList();
        reasons = reasons.stream().filter(Objects::nonNull).distinct().sorted().toList();
        if (reasons.isEmpty()) {
            throw new IllegalArgumentException("a field internalization decision requires a reason");
        }
        if (status == FieldInternalizationStatus.INTERNALIZED) {
            if (nativeSlotId.isEmpty()) {
                throw new IllegalArgumentException("internalized field requires a native slot id");
            }
            if (!reasons.equals(List.of(FieldInternalizationReason.FIELD_INTERNALIZATION_ELIGIBLE))) {
                throw new IllegalArgumentException("internalized field must have only the eligible reason");
            }
        } else if (nativeSlotId.isPresent()) {
            throw new IllegalArgumentException("kept field cannot have a native slot id");
        }
    }

    public boolean internalized() {
        return status == FieldInternalizationStatus.INTERNALIZED;
    }

    public FieldInternalizationReason primaryReason() {
        return reasons.get(0);
    }

    @Override
    public int compareTo(NativeFieldInternalizationDecision other) {
        return field.compareTo(other.field);
    }
}
