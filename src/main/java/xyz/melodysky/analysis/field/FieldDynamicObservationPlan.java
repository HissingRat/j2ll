package xyz.melodysky.analysis.field;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable EXACT/OWNER/GLOBAL field-observer facts for one analysis world. */
public final class FieldDynamicObservationPlan {
    private final List<FieldDynamicObservation> observations;

    public FieldDynamicObservationPlan(Collection<FieldDynamicObservation> observations) {
        this.observations = Objects.requireNonNull(observations, "observations")
                .stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
    }

    public static FieldDynamicObservationPlan empty() {
        return new FieldDynamicObservationPlan(List.of());
    }

    public List<FieldDynamicObservation> observations() {
        return observations;
    }

    public Set<FieldDynamicBoundaryKind> observerKindsFor(FieldId field) {
        Objects.requireNonNull(field, "field");
        EnumSet<FieldDynamicBoundaryKind> result = EnumSet.noneOf(FieldDynamicBoundaryKind.class);
        for (FieldDynamicObservation observation : observations) {
            boolean matches = switch (observation.scope()) {
                case EXACT -> observation.exactField().orElseThrow().equals(field);
                case OWNER -> observation.owner().orElseThrow().equals(field.owner());
                case GLOBAL -> true;
            };
            if (matches) {
                result.add(observation.observerKind());
            }
        }
        return Set.copyOf(result);
    }

    public boolean hasGlobalObservation() {
        return observations.stream().anyMatch(observation ->
                observation.scope() == FieldObservationScope.GLOBAL);
    }
}
