package xyz.melodysky.analysis.field;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

public record NativeFieldInternalizationPlan(List<NativeFieldInternalizationDecision> decisions) {
    public NativeFieldInternalizationPlan {
        decisions = decisions.stream().filter(Objects::nonNull).sorted().toList();
        long distinctFields = decisions.stream().map(NativeFieldInternalizationDecision::field).distinct().count();
        if (distinctFields != decisions.size()) {
            throw new IllegalArgumentException("field internalization plan contains duplicate decisions");
        }
        long distinctSlots = decisions.stream()
                .flatMap(decision -> decision.nativeSlotId().stream())
                .distinct()
                .count();
        long slotCount = decisions.stream().filter(NativeFieldInternalizationDecision::internalized).count();
        if (distinctSlots != slotCount) {
            throw new IllegalArgumentException("field internalization plan contains duplicate native slots");
        }
    }

    public Optional<NativeFieldInternalizationDecision> decisionFor(FieldId field) {
        return decisions.stream().filter(decision -> decision.field().equals(field)).findFirst();
    }

    public List<NativeFieldInternalizationDecision> internalizedFields() {
        return decisions.stream().filter(NativeFieldInternalizationDecision::internalized).toList();
    }

    public Set<FieldId> approvedFieldIds() {
        TreeSet<FieldId> fields = new TreeSet<>();
        internalizedFields().forEach(decision -> fields.add(decision.field()));
        return Collections.unmodifiableSet(new LinkedHashSet<>(fields));
    }

    public NativeFieldStorageKind storageKind(NativeFieldInternalizationDecision decision) {
        Objects.requireNonNull(decision, "decision");
        if (!decision.internalized() || !decisions.contains(decision)) {
            throw new IllegalArgumentException("field decision is not internalized by this plan");
        }
        return NativeFieldStorageKind.fromDescriptor(decision.field().descriptor())
                .orElseThrow(() -> new IllegalStateException(
                        "internalized field has unsupported descriptor"));
    }

    /**
     * Number of reference cells needed by the largest defining-class sidecar.
     *
     * <p>Reference indices are dense per defining class. A single generated
     * Loader shape can therefore serve every input class without embedding
     * class or field identities in the Loader.</p>
     */
    public int referenceSidecarSize() {
        return internalizedFields().stream()
                .filter(decision -> NativeFieldStorageKind.fromDescriptor(
                                decision.field().descriptor())
                        .filter(NativeFieldStorageKind::reference)
                        .isPresent())
                .collect(java.util.stream.Collectors.groupingBy(
                        decision -> decision.field().owner(),
                        java.util.stream.Collectors.counting()))
                .values().stream()
                .mapToInt(Math::toIntExact)
                .max()
                .orElse(0);
    }

    public int referenceIndex(NativeFieldInternalizationDecision decision) {
        Objects.requireNonNull(decision, "decision");
        if (storageKind(decision) != NativeFieldStorageKind.REFERENCE) {
            return -1;
        }
        FieldId field = decision.field();
        int index = 0;
        for (NativeFieldInternalizationDecision candidate : internalizedFields()) {
            if (!candidate.field().owner().equals(field.owner())) {
                continue;
            }
            boolean reference = NativeFieldStorageKind.fromDescriptor(
                            candidate.field().descriptor())
                    .filter(NativeFieldStorageKind::reference)
                    .isPresent();
            if (!reference) {
                continue;
            }
            if (candidate.field().equals(field)) {
                return index;
            }
            index++;
        }
        throw new IllegalArgumentException("field is not an internalized reference slot: " + field);
    }
}
