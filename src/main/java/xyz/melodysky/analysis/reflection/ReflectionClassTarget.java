package xyz.melodysky.analysis.reflection;

import java.util.Objects;

public record ReflectionClassTarget(
        String internalName,
        boolean requiresClassInitialization,
        String sourceSite) implements Comparable<ReflectionClassTarget> {
    public ReflectionClassTarget {
        Objects.requireNonNull(internalName, "internalName");
        Objects.requireNonNull(sourceSite, "sourceSite");
    }

    @Override
    public int compareTo(ReflectionClassTarget other) {
        int byClass = internalName.compareTo(other.internalName);
        if (byClass != 0) {
            return byClass;
        }
        int byInit = Boolean.compare(requiresClassInitialization, other.requiresClassInitialization);
        if (byInit != 0) {
            return byInit;
        }
        return sourceSite.compareTo(other.sourceSite);
    }
}
