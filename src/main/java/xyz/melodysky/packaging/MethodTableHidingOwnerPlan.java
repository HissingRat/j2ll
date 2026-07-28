package xyz.melodysky.packaging;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record MethodTableHidingOwnerPlan(
        String registrationOwner,
        List<MethodTableHidingEntry> registrationOrder) {
    public MethodTableHidingOwnerPlan {
        Objects.requireNonNull(registrationOwner, "registrationOwner");
        registrationOrder = List.copyOf(
                Objects.requireNonNull(registrationOrder, "registrationOrder"));
        if (registrationOrder.isEmpty()) {
            throw new IllegalArgumentException(
                    "method-table owner plan requires a non-empty registration order");
        }
        if (registrationOrder.stream()
                .anyMatch(entry -> !entry.registration().registrationOwner().equals(registrationOwner))) {
            throw new IllegalArgumentException("owner plan contains a foreign registration binding");
        }
        long distinctTokens = registrationOrder.stream()
                .map(MethodTableHidingEntry::token)
                .distinct()
                .count();
        if (distinctTokens != registrationOrder.size()) {
            throw new IllegalArgumentException(
                    "method-table report tokens must be collision-free per owner");
        }
    }

    public Optional<NativeRegistrationEntry> lookup(long token) {
        return registrationOrder.stream()
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
        return registrationOrder.stream()
                .map(entry -> entry.registration().nativeSymbol())
                .sorted(Comparator.naturalOrder())
                .toList();
    }
}
