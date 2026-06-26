package xyz.melodysky.analysis.reflection;

import java.util.Objects;
import xyz.melodysky.jvm.MethodSignature;

public record ReflectionMethodTarget(
        String owner,
        String name,
        String descriptor,
        ReflectionMethodKind kind,
        boolean requiresClassInitialization,
        String sourceSite) implements Comparable<ReflectionMethodTarget> {
    public ReflectionMethodTarget {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(sourceSite, "sourceSite");
    }

    public MethodSignature signature() {
        return new MethodSignature(name, descriptor);
    }

    public String methodKey() {
        return owner + "#" + name + "!" + descriptor;
    }

    @Override
    public int compareTo(ReflectionMethodTarget other) {
        int byOwner = owner.compareTo(other.owner);
        if (byOwner != 0) {
            return byOwner;
        }
        int byName = name.compareTo(other.name);
        if (byName != 0) {
            return byName;
        }
        int byDescriptor = descriptor.compareTo(other.descriptor);
        if (byDescriptor != 0) {
            return byDescriptor;
        }
        return kind.compareTo(other.kind);
    }
}
