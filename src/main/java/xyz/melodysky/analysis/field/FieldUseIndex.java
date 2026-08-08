package xyz.melodysky.analysis.field;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import xyz.melodysky.frontend.classfile.ParsedField;

public final class FieldUseIndex {
    private final List<ParsedField> inputBaseFields;
    private final Map<FieldId, ParsedField> inputBaseDeclarations;
    private final Map<FieldId, List<FieldAccessSite>> accesses;
    private final Set<FieldId> ambiguousInputBaseFields;
    private final Set<String> multiReleaseOwners;
    private final Set<String> ownersWithClassInitializer;
    private final Set<String> serializableOwners;
    private final List<FieldDynamicBoundary> dynamicBoundaries;
    private final List<UnresolvedFieldReference> unresolvedReferences;
    private final FieldDynamicObservationPlan dynamicObservationPlan;

    FieldUseIndex(
            List<ParsedField> inputBaseFields,
            Map<FieldId, List<FieldAccessSite>> accesses,
            Set<FieldId> ambiguousInputBaseFields,
            Set<String> multiReleaseOwners,
            Set<String> ownersWithClassInitializer,
            Set<String> serializableOwners,
            List<FieldDynamicBoundary> dynamicBoundaries,
            List<UnresolvedFieldReference> unresolvedReferences,
            FieldDynamicObservationPlan dynamicObservationPlan) {
        ArrayList<ParsedField> sortedFields = new ArrayList<>(Objects.requireNonNull(inputBaseFields, "inputBaseFields"));
        sortedFields.sort(Comparator.comparing(FieldUseIndex::id));
        this.inputBaseFields = List.copyOf(sortedFields);

        LinkedHashMap<FieldId, ParsedField> declarations = new LinkedHashMap<>();
        for (ParsedField field : sortedFields) {
            declarations.putIfAbsent(id(field), field);
        }
        this.inputBaseDeclarations = Collections.unmodifiableMap(declarations);

        LinkedHashMap<FieldId, List<FieldAccessSite>> sortedAccesses = new LinkedHashMap<>();
        Objects.requireNonNull(accesses, "accesses").entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> sortedAccesses.put(entry.getKey(), entry.getValue().stream().sorted().toList()));
        this.accesses = Collections.unmodifiableMap(sortedAccesses);
        this.ambiguousInputBaseFields = immutableSortedSet(ambiguousInputBaseFields);
        this.multiReleaseOwners = immutableSortedSet(multiReleaseOwners);
        this.ownersWithClassInitializer = immutableSortedSet(ownersWithClassInitializer);
        this.serializableOwners = immutableSortedSet(serializableOwners);
        this.dynamicBoundaries = dynamicBoundaries.stream().filter(Objects::nonNull).sorted().toList();
        this.unresolvedReferences = unresolvedReferences.stream().filter(Objects::nonNull).sorted().toList();
        this.dynamicObservationPlan = Objects.requireNonNull(
                dynamicObservationPlan,
                "dynamicObservationPlan");
    }

    public List<ParsedField> inputBaseFields() {
        return inputBaseFields;
    }

    public Optional<ParsedField> inputBaseDeclaration(FieldId field) {
        return Optional.ofNullable(inputBaseDeclarations.get(field));
    }

    public List<FieldAccessSite> accessesFor(FieldId field) {
        return accesses.getOrDefault(field, List.of());
    }

    public Map<FieldId, List<FieldAccessSite>> accesses() {
        return accesses;
    }

    public boolean hasAmbiguousInputBaseDeclaration(FieldId field) {
        return ambiguousInputBaseFields.contains(field);
    }

    public Set<FieldId> ambiguousInputBaseFields() {
        return ambiguousInputBaseFields;
    }

    public boolean hasMultiReleaseCounterpart(String owner) {
        return multiReleaseOwners.contains(owner);
    }

    public Set<String> multiReleaseOwners() {
        return multiReleaseOwners;
    }

    public boolean hasClassInitializer(String owner) {
        return ownersWithClassInitializer.contains(owner);
    }

    public Set<String> ownersWithClassInitializer() {
        return ownersWithClassInitializer;
    }

    public boolean isSerializableOwner(String owner) {
        return serializableOwners.contains(owner);
    }

    public Set<String> serializableOwners() {
        return serializableOwners;
    }

    public List<FieldDynamicBoundary> dynamicBoundaries() {
        return dynamicBoundaries;
    }

    public List<FieldDynamicBoundary> dynamicBoundariesForOwner(String owner) {
        Objects.requireNonNull(owner, "owner");
        return dynamicBoundaries.stream()
                .filter(boundary -> boundary.methodOwner().equals(owner))
                .toList();
    }

    public List<UnresolvedFieldReference> unresolvedReferences() {
        return unresolvedReferences;
    }

    public boolean hasUnresolvedReferenceForOwner(String owner) {
        Objects.requireNonNull(owner, "owner");
        return unresolvedReferences.stream()
                .anyMatch(reference -> reference.symbolicOwner().equals(owner));
    }

    public FieldDynamicObservationPlan dynamicObservationPlan() {
        return dynamicObservationPlan;
    }

    public Set<FieldDynamicBoundaryKind> dynamicObserverKindsFor(FieldId field) {
        return dynamicObservationPlan.observerKindsFor(
                Objects.requireNonNull(field, "field"));
    }

    public FieldUseIndex withAdditionalMultiReleaseOwners(Set<String> owners) {
        TreeSet<String> combined = new TreeSet<>(multiReleaseOwners);
        combined.addAll(Objects.requireNonNull(owners, "owners"));
        if (combined.equals(multiReleaseOwners)) {
            return this;
        }
        return new FieldUseIndex(
                inputBaseFields,
                accesses,
                ambiguousInputBaseFields,
                combined,
                ownersWithClassInitializer,
                serializableOwners,
                dynamicBoundaries,
                unresolvedReferences,
                dynamicObservationPlan);
    }

    private static FieldId id(ParsedField field) {
        return new FieldId(field.owner(), field.name(), field.descriptor());
    }

    private static <T extends Comparable<? super T>> Set<T> immutableSortedSet(Set<T> values) {
        TreeSet<T> sorted = new TreeSet<>(Objects.requireNonNull(values, "values"));
        return Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
    }
}
