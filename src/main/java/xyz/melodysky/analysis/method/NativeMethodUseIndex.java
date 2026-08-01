package xyz.melodysky.analysis.method;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record NativeMethodUseIndex(
        Map<NativeMethodId, List<NativeMethodCallUse>> incomingCalls,
        Set<NativeMethodId> methodHandleReferences,
        Set<NativeMethodId> reflectionObservers,
        Set<NativeMethodId> enclosingMethodReferences) {
    public NativeMethodUseIndex {
        LinkedHashMap<NativeMethodId, List<NativeMethodCallUse>> stableCalls =
                new LinkedHashMap<>();
        Objects.requireNonNull(incomingCalls, "incomingCalls")
                .entrySet()
                .stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> stableCalls.put(
                        entry.getKey(),
                        entry.getValue().stream()
                                .filter(Objects::nonNull)
                                .distinct()
                                .sorted()
                                .toList()));
        incomingCalls = Collections.unmodifiableMap(stableCalls);
        methodHandleReferences = stableIds(
                methodHandleReferences,
                "methodHandleReferences");
        reflectionObservers = stableIds(
                reflectionObservers,
                "reflectionObservers");
        enclosingMethodReferences = stableIds(
                enclosingMethodReferences,
                "enclosingMethodReferences");
    }

    public List<NativeMethodCallUse> incomingCalls(NativeMethodId method) {
        return incomingCalls.getOrDefault(method, List.of());
    }

    private static Set<NativeMethodId> stableIds(
            Set<NativeMethodId> values,
            String name) {
        LinkedHashSet<NativeMethodId> result = new LinkedHashSet<>();
        Objects.requireNonNull(values, name).stream()
                .filter(Objects::nonNull)
                .sorted()
                .forEach(result::add);
        return Collections.unmodifiableSet(result);
    }
}
