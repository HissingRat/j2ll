package xyz.melodysky.analysis.field;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public record NativeFieldInternalizationPlan(
        List<NativeFieldInternalizationDecision> decisions,
        Map<String, Map<FieldId, Integer>> referenceIndicesByOwner) {
    /**
     * Compatibility constructor for focused fixtures.
     *
     * <p>Production planning supplies an explicit diversified mapping through
     * the canonical constructor.</p>
     */
    public NativeFieldInternalizationPlan(
            List<NativeFieldInternalizationDecision> decisions) {
        this(decisions, canonicalReferenceIndices(decisions));
    }

    public NativeFieldInternalizationPlan {
        decisions = decisions.stream()
                .filter(Objects::nonNull)
                .sorted()
                .toList();
        validateDecisions(decisions);
        referenceIndicesByOwner = immutableReferenceIndices(referenceIndicesByOwner);
        validateReferenceIndices(decisions, referenceIndicesByOwner);
    }

    public java.util.Optional<NativeFieldInternalizationDecision> decisionFor(
            FieldId field) {
        return decisions.stream()
                .filter(decision -> decision.field().equals(field))
                .findFirst();
    }

    public List<NativeFieldInternalizationDecision> internalizedFields() {
        return decisions.stream()
                .filter(NativeFieldInternalizationDecision::internalized)
                .toList();
    }

    public List<NativeFieldInternalizationDecision> nativeStoredFields() {
        return decisions.stream()
                .filter(NativeFieldInternalizationDecision::nativeStored)
                .toList();
    }

    public List<NativeFieldInternalizationDecision> constantFoldedFields() {
        return decisions.stream()
                .filter(NativeFieldInternalizationDecision::constantFolded)
                .toList();
    }

    public Set<FieldId> approvedFieldIds() {
        TreeSet<FieldId> fields = new TreeSet<>();
        internalizedFields().forEach(decision -> fields.add(decision.field()));
        return Collections.unmodifiableSet(new LinkedHashSet<>(fields));
    }

    public NativeFieldStorageKind storageKind(
            NativeFieldInternalizationDecision decision) {
        Objects.requireNonNull(decision, "decision");
        if (!decision.nativeStored() || !decisions.contains(decision)) {
            throw new IllegalArgumentException(
                    "field decision does not use native slot storage in this plan");
        }
        return NativeFieldStorageKind.fromDescriptor(decision.field().descriptor())
                .orElseThrow(() -> new IllegalStateException(
                        "internalized field has unsupported descriptor"));
    }

    /**
     * Number of reference cells needed by the largest defining-class sidecar.
     */
    public int referenceSidecarSize() {
        return referenceIndicesByOwner.values().stream()
                .mapToInt(Map::size)
                .max()
                .orElse(0);
    }

    public int referenceIndex(NativeFieldInternalizationDecision decision) {
        Objects.requireNonNull(decision, "decision");
        if (storageKind(decision) != NativeFieldStorageKind.REFERENCE) {
            return -1;
        }
        FieldId field = decision.field();
        Integer index = referenceIndicesByOwner
                .getOrDefault(field.owner(), Map.of())
                .get(field);
        if (index == null) {
            throw new IllegalArgumentException(
                    "field is not an internalized reference slot: " + field);
        }
        return index;
    }

    private static void validateDecisions(
            List<NativeFieldInternalizationDecision> decisions) {
        long distinctFields = decisions.stream()
                .map(NativeFieldInternalizationDecision::field)
                .distinct()
                .count();
        if (distinctFields != decisions.size()) {
            throw new IllegalArgumentException(
                    "field internalization plan contains duplicate decisions");
        }
        long distinctSlots = decisions.stream()
                .flatMap(decision -> decision.nativeSlotId().stream())
                .distinct()
                .count();
        long slotCount = decisions.stream()
                .filter(NativeFieldInternalizationDecision::nativeStored)
                .count();
        if (distinctSlots != slotCount) {
            throw new IllegalArgumentException(
                    "field internalization plan contains duplicate native slots");
        }
    }

    private static Map<String, Map<FieldId, Integer>> canonicalReferenceIndices(
            List<NativeFieldInternalizationDecision> decisions) {
        Objects.requireNonNull(decisions, "decisions");
        TreeMap<String, ArrayList<FieldId>> fieldsByOwner = new TreeMap<>();
        decisions.stream()
                .filter(Objects::nonNull)
                .filter(NativeFieldInternalizationDecision::nativeStored)
                .map(NativeFieldInternalizationDecision::field)
                .filter(NativeFieldInternalizationPlan::isReference)
                .sorted()
                .forEach(field -> fieldsByOwner
                        .computeIfAbsent(field.owner(), ignored -> new ArrayList<>())
                        .add(field));
        LinkedHashMap<String, Map<FieldId, Integer>> result = new LinkedHashMap<>();
        fieldsByOwner.forEach((owner, fields) -> {
            LinkedHashMap<FieldId, Integer> indices = new LinkedHashMap<>();
            for (int index = 0; index < fields.size(); index++) {
                indices.put(fields.get(index), index);
            }
            result.put(owner, indices);
        });
        return result;
    }

    private static Map<String, Map<FieldId, Integer>> immutableReferenceIndices(
            Map<String, Map<FieldId, Integer>> source) {
        Objects.requireNonNull(source, "referenceIndicesByOwner");
        TreeMap<String, Map<FieldId, Integer>> sortedOwners = new TreeMap<>();
        source.forEach((owner, indices) -> {
            Objects.requireNonNull(owner, "reference sidecar owner");
            Objects.requireNonNull(indices, "reference sidecar indices");
            TreeMap<FieldId, Integer> sortedFields = new TreeMap<>();
            indices.forEach((field, index) -> sortedFields.put(
                    Objects.requireNonNull(field, "reference sidecar field"),
                    Objects.requireNonNull(index, "reference sidecar index")));
            sortedOwners.put(
                    owner,
                    Collections.unmodifiableMap(new LinkedHashMap<>(sortedFields)));
        });
        return Collections.unmodifiableMap(new LinkedHashMap<>(sortedOwners));
    }

    private static void validateReferenceIndices(
            List<NativeFieldInternalizationDecision> decisions,
            Map<String, Map<FieldId, Integer>> indicesByOwner) {
        TreeMap<FieldId, NativeFieldInternalizationDecision> internalized =
                new TreeMap<>();
        for (NativeFieldInternalizationDecision decision : decisions) {
            if (decision.nativeStored()) {
                internalized.put(decision.field(), decision);
            }
        }
        TreeMap<String, Set<FieldId>> expectedByOwner = new TreeMap<>();
        internalized.keySet().stream()
                .filter(NativeFieldInternalizationPlan::isReference)
                .forEach(field -> expectedByOwner
                        .computeIfAbsent(field.owner(), ignored -> new TreeSet<>())
                        .add(field));

        if (!indicesByOwner.keySet().equals(expectedByOwner.keySet())) {
            throw new IllegalArgumentException(
                    "reference sidecar owners do not match internalized reference fields");
        }
        for (Map.Entry<String, Map<FieldId, Integer>> ownerEntry
                : indicesByOwner.entrySet()) {
            String owner = ownerEntry.getKey();
            Map<FieldId, Integer> indices = ownerEntry.getValue();
            for (FieldId field : indices.keySet()) {
                if (!owner.equals(field.owner())) {
                    throw new IllegalArgumentException(
                            "reference sidecar field is assigned to the wrong owner: " + field);
                }
                NativeFieldInternalizationDecision decision = internalized.get(field);
                if (decision == null || !isReference(field)) {
                    throw new IllegalArgumentException(
                            "primitive, kept, or unknown field has a reference sidecar index: "
                                    + field);
                }
            }
            if (!indices.keySet().equals(expectedByOwner.get(owner))) {
                throw new IllegalArgumentException(
                        "reference sidecar fields do not match owner plan: " + owner);
            }
            TreeSet<Integer> actualIndices = new TreeSet<>(indices.values());
            if (actualIndices.size() != indices.size()) {
                throw new IllegalArgumentException(
                        "reference sidecar indices contain duplicates for owner: " + owner);
            }
            int expectedIndex = 0;
            for (int actualIndex : actualIndices) {
                if (actualIndex != expectedIndex) {
                    throw new IllegalArgumentException(
                            "reference sidecar indices must be dense from zero for owner: "
                                    + owner);
                }
                expectedIndex++;
            }
        }
    }

    private static boolean isReference(FieldId field) {
        return NativeFieldStorageKind.fromDescriptor(field.descriptor())
                .filter(NativeFieldStorageKind::reference)
                .isPresent();
    }
}
