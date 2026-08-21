package xyz.melodysky.analysis.runtime;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Immutable entry-rooted method reachability evidence produced by RTA. */
public record ReachabilityResult(
        Set<String> entryMethodKeys,
        Set<String> reachableMethodKeys,
        Set<String> unreachableMethodKeys,
        int fixedPointIterations) {
    public ReachabilityResult {
        entryMethodKeys = immutable(entryMethodKeys, "entryMethodKeys");
        reachableMethodKeys = immutable(reachableMethodKeys, "reachableMethodKeys");
        unreachableMethodKeys = immutable(unreachableMethodKeys, "unreachableMethodKeys");
        if (!reachableMethodKeys.containsAll(entryMethodKeys)) {
            throw new IllegalArgumentException("every entry method must be reachable");
        }
        TreeSet<String> overlap = new TreeSet<>(reachableMethodKeys);
        overlap.retainAll(unreachableMethodKeys);
        if (!overlap.isEmpty()) {
            throw new IllegalArgumentException("reachable and unreachable methods overlap: " + overlap);
        }
        if (fixedPointIterations < 0) {
            throw new IllegalArgumentException("fixedPointIterations must be non-negative");
        }
    }

    public static ReachabilityResult allReachable(
            Set<String> entryMethodKeys,
            Set<String> methodKeys) {
        return new ReachabilityResult(entryMethodKeys, methodKeys, Set.of(), 0);
    }

    private static Set<String> immutable(Set<String> values, String name) {
        return Collections.unmodifiableSet(new TreeSet<>(
                Objects.requireNonNull(values, name)));
    }
}
