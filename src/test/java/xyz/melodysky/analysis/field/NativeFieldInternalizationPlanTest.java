package xyz.melodysky.analysis.field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class NativeFieldInternalizationPlanTest {
    private static final String OWNER_A = "pkg/OwnerA";
    private static final String OWNER_B = "pkg/OwnerB";
    private static final FieldId A_FIRST =
            new FieldId(OWNER_A, "first", "Ljava/lang/Object;");
    private static final FieldId A_SECOND =
            new FieldId(OWNER_A, "second", "[I");
    private static final FieldId B_ONLY =
            new FieldId(OWNER_B, "only", "Ljava/lang/String;");
    private static final FieldId PRIMITIVE =
            new FieldId(OWNER_A, "count", "I");

    @Test
    void compatibilityConstructorUsesCanonicalDensePerOwnerIndices() {
        NativeFieldInternalizationDecision primitive = internalized(PRIMITIVE, "slot-p");
        NativeFieldInternalizationDecision second = internalized(A_SECOND, "slot-2");
        NativeFieldInternalizationDecision only = internalized(B_ONLY, "slot-b");
        NativeFieldInternalizationDecision first = internalized(A_FIRST, "slot-1");

        NativeFieldInternalizationPlan plan =
                new NativeFieldInternalizationPlan(List.of(second, primitive, only, first));

        assertEquals(0, plan.referenceIndex(first));
        assertEquals(1, plan.referenceIndex(second));
        assertEquals(0, plan.referenceIndex(only));
        assertEquals(-1, plan.referenceIndex(primitive));
        assertEquals(2, plan.referenceSidecarSize());
        assertEquals(
                Map.of(
                        OWNER_A, Map.of(A_FIRST, 0, A_SECOND, 1),
                        OWNER_B, Map.of(B_ONLY, 0)),
                plan.referenceIndicesByOwner());
    }

    @Test
    void explicitMappingIsTheOnlySourceOfReferenceIndices() {
        NativeFieldInternalizationDecision first = internalized(A_FIRST, "slot-1");
        NativeFieldInternalizationDecision second = internalized(A_SECOND, "slot-2");
        NativeFieldInternalizationPlan plan = new NativeFieldInternalizationPlan(
                List.of(first, second),
                Map.of(OWNER_A, Map.of(A_FIRST, 1, A_SECOND, 0)));

        assertEquals(1, plan.referenceIndex(first));
        assertEquals(0, plan.referenceIndex(second));
    }

    @Test
    void rejectsGapsDuplicatesWrongOwnersAndPrimitiveIndices() {
        NativeFieldInternalizationDecision first = internalized(A_FIRST, "slot-1");
        NativeFieldInternalizationDecision second = internalized(A_SECOND, "slot-2");
        NativeFieldInternalizationDecision primitive = internalized(PRIMITIVE, "slot-p");
        List<NativeFieldInternalizationDecision> references = List.of(first, second);

        assertThrows(
                IllegalArgumentException.class,
                () -> new NativeFieldInternalizationPlan(
                        references,
                        Map.of(OWNER_A, Map.of(A_FIRST, 0, A_SECOND, 0))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NativeFieldInternalizationPlan(
                        references,
                        Map.of(OWNER_A, Map.of(A_FIRST, 0, A_SECOND, 2))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NativeFieldInternalizationPlan(
                        references,
                        Map.of(OWNER_B, Map.of(A_FIRST, 0, A_SECOND, 1))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NativeFieldInternalizationPlan(
                        List.of(first, second, primitive),
                        Map.of(OWNER_A, Map.of(A_FIRST, 0, A_SECOND, 1, PRIMITIVE, 2))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NativeFieldInternalizationPlan(
                        references,
                        Map.of(OWNER_A, Map.of(A_FIRST, 0))));
    }

    private NativeFieldInternalizationDecision internalized(
            FieldId field,
            String slot) {
        return new NativeFieldInternalizationDecision(
                field,
                FieldInternalizationStatus.INTERNALIZED,
                Optional.of(slot),
                List.of(),
                List.of(FieldInternalizationReason.FIELD_INTERNALIZATION_ELIGIBLE));
    }
}
