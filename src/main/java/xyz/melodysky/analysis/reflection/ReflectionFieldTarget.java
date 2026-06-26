package xyz.melodysky.analysis.reflection;

import java.util.Objects;

public record ReflectionFieldTarget(
        String owner,
        String name,
        String descriptor,
        String sourceSite) implements Comparable<ReflectionFieldTarget> {
    public ReflectionFieldTarget {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(sourceSite, "sourceSite");
    }

    public String fieldKey() {
        return owner + "#" + name + "!" + descriptor;
    }

    @Override
    public int compareTo(ReflectionFieldTarget other) {
        int byOwner = owner.compareTo(other.owner);
        if (byOwner != 0) {
            return byOwner;
        }
        int byName = name.compareTo(other.name);
        if (byName != 0) {
            return byName;
        }
        return descriptor.compareTo(other.descriptor);
    }
}
