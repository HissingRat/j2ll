package xyz.melodysky.analysis.hierarchy;

import java.util.Objects;
import java.util.Set;

/** Facts needed when reporting default-interface dispatch boundaries. */
public record DefaultInterfaceAnalysis(
        Set<String> methodKeys,
        Set<String> conflictSignatures) {
    public DefaultInterfaceAnalysis {
        methodKeys = Set.copyOf(Objects.requireNonNull(methodKeys, "methodKeys"));
        conflictSignatures = Set.copyOf(Objects.requireNonNull(conflictSignatures, "conflictSignatures"));
    }
}
