package xyz.melodysky.analysis.runtime;

import java.util.Comparator;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public record RuntimeTypeResult(
        Set<String> instantiatedClasses,
        boolean conservative,
        List<AllocationSite> allocationSites) {
    public RuntimeTypeResult {
        instantiatedClasses = Collections.unmodifiableSet(new TreeSet<>(
                Objects.requireNonNull(instantiatedClasses, "instantiatedClasses")));
        allocationSites = allocationSites.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(AllocationSite::id))
                .toList();
    }
}
