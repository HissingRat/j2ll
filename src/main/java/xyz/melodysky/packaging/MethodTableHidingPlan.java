package xyz.melodysky.packaging;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record MethodTableHidingPlan(
        boolean enabled,
        String planId,
        List<MethodTableHidingOwnerPlan> owners) {
    public MethodTableHidingPlan {
        Objects.requireNonNull(planId, "planId");
        owners = owners.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(MethodTableHidingOwnerPlan::registrationOwner))
                .toList();
        if (!enabled && !owners.isEmpty()) {
            throw new IllegalArgumentException("disabled method-table plan must be empty");
        }
    }

    public static MethodTableHidingPlan disabled() {
        return new MethodTableHidingPlan(false, "disabled", List.of());
    }

    public Optional<MethodTableHidingOwnerPlan> owner(String internalName) {
        return owners.stream()
                .filter(owner -> owner.registrationOwner().equals(internalName))
                .findFirst();
    }

    public boolean changed() {
        return enabled && !owners.isEmpty();
    }
}
