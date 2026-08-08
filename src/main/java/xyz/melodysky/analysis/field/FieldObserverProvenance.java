package xyz.melodysky.analysis.field;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Internal union domain for field-bearing runtime values. */
final class FieldObserverProvenance {
    private final Set<FieldId> exactFields;
    private final Set<String> owners;
    private final boolean global;
    private final boolean nonField;

    private FieldObserverProvenance(
            Collection<FieldId> exactFields,
            Collection<String> owners,
            boolean global,
            boolean nonField) {
        this.exactFields = Set.copyOf(exactFields);
        this.owners = Set.copyOf(owners);
        this.global = global;
        this.nonField = nonField;
    }

    static FieldObserverProvenance exact(FieldId field) {
        return new FieldObserverProvenance(List.of(field), List.of(), false, false);
    }

    static FieldObserverProvenance exact(Collection<FieldId> fields) {
        return new FieldObserverProvenance(fields, List.of(), false, fields.isEmpty());
    }

    static FieldObserverProvenance owner(String owner) {
        return new FieldObserverProvenance(List.of(), List.of(owner), false, false);
    }

    static FieldObserverProvenance global() {
        return new FieldObserverProvenance(List.of(), List.of(), true, false);
    }

    static FieldObserverProvenance nonField() {
        return new FieldObserverProvenance(List.of(), List.of(), false, true);
    }

    static FieldObserverProvenance union(Collection<FieldObserverProvenance> values) {
        LinkedHashSet<FieldId> fields = new LinkedHashSet<>();
        LinkedHashSet<String> owners = new LinkedHashSet<>();
        boolean global = false;
        boolean nonField = false;
        for (FieldObserverProvenance value : values) {
            if (value == null) {
                global = true;
                continue;
            }
            fields.addAll(value.exactFields);
            owners.addAll(value.owners);
            global |= value.global;
            nonField |= value.nonField;
        }
        if (global) {
            return global();
        }
        return new FieldObserverProvenance(fields, owners, false, nonField);
    }

    /** Combines independent Unsafe base/offset constraints. */
    static FieldObserverProvenance constrain(
            FieldObserverProvenance first,
            FieldObserverProvenance second) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        if (first.nonField && !first.hasFieldScope()) {
            return second;
        }
        if (second.nonField && !second.hasFieldScope()) {
            return first;
        }
        if (first.global || second.global) {
            return global();
        }
        LinkedHashSet<FieldId> exact = new LinkedHashSet<>(first.exactFields);
        exact.retainAll(second.exactFields);
        if (!exact.isEmpty()) {
            return exact(exact);
        }
        LinkedHashSet<FieldId> narrowed = new LinkedHashSet<>();
        for (FieldId field : first.exactFields) {
            if (second.owners.contains(field.owner())) {
                narrowed.add(field);
            }
        }
        for (FieldId field : second.exactFields) {
            if (first.owners.contains(field.owner())) {
                narrowed.add(field);
            }
        }
        if (!narrowed.isEmpty()) {
            return exact(narrowed);
        }
        LinkedHashSet<String> commonOwners = new LinkedHashSet<>(first.owners);
        commonOwners.retainAll(second.owners);
        if (!commonOwners.isEmpty()) {
            return new FieldObserverProvenance(List.of(), commonOwners, false, false);
        }
        if (first.hasFieldScope() && second.hasFieldScope()) {
            return global();
        }
        return first.hasFieldScope() ? first : second;
    }

    Set<FieldId> exactFields() {
        return exactFields;
    }

    Set<String> owners() {
        return owners;
    }

    boolean globalScope() {
        return global;
    }

    boolean nonFieldValue() {
        return nonField && !hasFieldScope();
    }

    boolean hasFieldScope() {
        return global || !exactFields.isEmpty() || !owners.isEmpty();
    }
}
