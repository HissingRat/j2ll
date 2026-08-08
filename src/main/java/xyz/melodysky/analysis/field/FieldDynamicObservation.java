package xyz.melodysky.analysis.field;

import java.util.Objects;
import java.util.Optional;

/** A conservative field-observer fact recovered from bytecode value provenance. */
public record FieldDynamicObservation(
        FieldObservationScope scope,
        FieldDynamicBoundaryKind observerKind,
        Optional<FieldId> exactField,
        Optional<String> owner,
        String methodKey,
        int instructionIndex) implements Comparable<FieldDynamicObservation> {
    public FieldDynamicObservation {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(observerKind, "observerKind");
        exactField = Objects.requireNonNull(exactField, "exactField");
        owner = Objects.requireNonNull(owner, "owner").filter(value -> !value.isBlank());
        Objects.requireNonNull(methodKey, "methodKey");
        if (instructionIndex < 0) {
            throw new IllegalArgumentException("instructionIndex must be non-negative");
        }
        switch (scope) {
            case EXACT -> {
                if (exactField.isEmpty() || owner.isPresent()) {
                    throw new IllegalArgumentException("EXACT requires only exactField");
                }
            }
            case OWNER -> {
                if (exactField.isPresent() || owner.isEmpty()) {
                    throw new IllegalArgumentException("OWNER requires only owner");
                }
            }
            case GLOBAL -> {
                if (exactField.isPresent() || owner.isPresent()) {
                    throw new IllegalArgumentException("GLOBAL carries no target");
                }
            }
        }
    }

    public static FieldDynamicObservation exact(
            FieldDynamicBoundaryKind observerKind,
            FieldId field,
            String methodKey,
            int instructionIndex) {
        return new FieldDynamicObservation(
                FieldObservationScope.EXACT,
                observerKind,
                Optional.of(field),
                Optional.empty(),
                methodKey,
                instructionIndex);
    }

    public static FieldDynamicObservation owner(
            FieldDynamicBoundaryKind observerKind,
            String owner,
            String methodKey,
            int instructionIndex) {
        return new FieldDynamicObservation(
                FieldObservationScope.OWNER,
                observerKind,
                Optional.empty(),
                Optional.of(owner),
                methodKey,
                instructionIndex);
    }

    public static FieldDynamicObservation global(
            FieldDynamicBoundaryKind observerKind,
            String methodKey,
            int instructionIndex) {
        return new FieldDynamicObservation(
                FieldObservationScope.GLOBAL,
                observerKind,
                Optional.empty(),
                Optional.empty(),
                methodKey,
                instructionIndex);
    }

    @Override
    public int compareTo(FieldDynamicObservation other) {
        int byMethod = methodKey.compareTo(other.methodKey);
        if (byMethod != 0) {
            return byMethod;
        }
        int byInstruction = Integer.compare(instructionIndex, other.instructionIndex);
        if (byInstruction != 0) {
            return byInstruction;
        }
        int byKind = observerKind.compareTo(other.observerKind);
        if (byKind != 0) {
            return byKind;
        }
        int byScope = scope.compareTo(other.scope);
        if (byScope != 0) {
            return byScope;
        }
        int byOwner = owner.orElse("").compareTo(other.owner.orElse(""));
        if (byOwner != 0) {
            return byOwner;
        }
        return exactField.map(FieldId::fieldKey)
                .orElse("")
                .compareTo(other.exactField.map(FieldId::fieldKey).orElse(""));
    }
}
