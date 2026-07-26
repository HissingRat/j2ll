package xyz.melodysky.packaging;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record MethodTableHidingOwnerPlan(
        String registrationOwner,
        long tokenMask,
        List<MethodTableHidingEntry> metadataOrder,
        List<MethodTableHidingEntry> functionOrder) {
    public MethodTableHidingOwnerPlan {
        Objects.requireNonNull(registrationOwner, "registrationOwner");
        metadataOrder = List.copyOf(Objects.requireNonNull(metadataOrder, "metadataOrder"));
        functionOrder = List.copyOf(Objects.requireNonNull(functionOrder, "functionOrder"));
        if (metadataOrder.isEmpty() || metadataOrder.size() != functionOrder.size()) {
            throw new IllegalArgumentException("method-table owner plan requires matching non-empty tables");
        }
        List<MethodTableHidingEntry> canonicalMetadata = metadataOrder.stream()
                .sorted()
                .toList();
        List<MethodTableHidingEntry> canonicalFunctions = functionOrder.stream()
                .sorted()
                .toList();
        if (!canonicalMetadata.equals(canonicalFunctions)) {
            throw new IllegalArgumentException("metadata and function tables must contain the same bindings");
        }
        if (metadataOrder.stream()
                .anyMatch(entry -> !entry.registration().registrationOwner().equals(registrationOwner))) {
            throw new IllegalArgumentException("owner plan contains a foreign registration binding");
        }
        long distinctTokens = metadataOrder.stream()
                .map(MethodTableHidingEntry::token)
                .distinct()
                .count();
        if (distinctTokens != metadataOrder.size()) {
            throw new IllegalArgumentException("method-table tokens must be collision-free per owner");
        }
    }

    public Optional<NativeRegistrationEntry> lookup(long token) {
        return metadataOrder.stream()
                .filter(entry -> entry.token() == token)
                .map(MethodTableHidingEntry::registration)
                .findFirst();
    }

    public NativeRegistrationEntry require(long token) {
        return lookup(token).orElseThrow(() ->
                new IllegalArgumentException("unknown method-table token "
                        + Long.toUnsignedString(token, 16)));
    }

    public List<String> affectedSymbols() {
        return metadataOrder.stream()
                .map(entry -> entry.registration().nativeSymbol())
                .sorted(Comparator.naturalOrder())
                .toList();
    }
}
