package xyz.melodysky.testsupport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import xyz.melodysky.analysis.field.FieldAccessSite;
import xyz.melodysky.analysis.field.FieldId;
import xyz.melodysky.analysis.field.FieldInternalizationReason;
import xyz.melodysky.analysis.field.FieldInternalizationStatus;
import xyz.melodysky.analysis.field.NativeFieldInternalizationDecision;
import xyz.melodysky.analysis.field.NativeFieldInternalizationPlan;
import xyz.melodysky.analysis.field.NativeFieldInternalizationStorage;
import xyz.melodysky.analysis.field.NativeFieldStorageKind;

/** Explicit canonical fixtures for tests that do not exercise diversified field layout. */
public final class NativeFieldInternalizationFixtures {
    private NativeFieldInternalizationFixtures() {}

    public static NativeFieldInternalizationPlan plan(
            List<NativeFieldInternalizationDecision> decisions) {
        TreeMap<String, ArrayList<FieldId>> references = new TreeMap<>();
        decisions.stream()
                .filter(NativeFieldInternalizationDecision::nativeStored)
                .map(NativeFieldInternalizationDecision::field)
                .filter(field -> NativeFieldStorageKind.fromDescriptor(field.descriptor())
                        .filter(NativeFieldStorageKind::reference)
                        .isPresent())
                .sorted()
                .forEach(field -> references
                        .computeIfAbsent(field.owner(), ignored -> new ArrayList<>())
                        .add(field));
        LinkedHashMap<String, Map<FieldId, Integer>> indices = new LinkedHashMap<>();
        references.forEach((owner, fields) -> {
            LinkedHashMap<FieldId, Integer> ownerIndices = new LinkedHashMap<>();
            for (int index = 0; index < fields.size(); index++) {
                ownerIndices.put(fields.get(index), index);
            }
            indices.put(owner, ownerIndices);
        });
        return new NativeFieldInternalizationPlan(decisions, indices);
    }

    public static NativeFieldInternalizationDecision nativeStored(
            FieldId field,
            String slot,
            List<FieldAccessSite> accesses) {
        return new NativeFieldInternalizationDecision(
                field,
                FieldInternalizationStatus.INTERNALIZED,
                NativeFieldInternalizationStorage.NATIVE_SLOT,
                java.util.Optional.of(slot),
                java.util.Optional.empty(),
                accesses,
                List.of(FieldInternalizationReason.FIELD_INTERNALIZATION_ELIGIBLE));
    }
}
