package xyz.melodysky.analysis.reflection;

import java.util.Objects;

public record ReflectionUnsupportedSite(
        String owner,
        String method,
        String descriptor,
        int instructionIndex,
        String reasonCode,
        String reason) implements Comparable<ReflectionUnsupportedSite> {
    public ReflectionUnsupportedSite {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(reasonCode, "reasonCode");
        Objects.requireNonNull(reason, "reason");
    }

    public String artifactId() {
        return owner + "#" + method + "!" + descriptor + "@" + instructionIndex;
    }

    @Override
    public int compareTo(ReflectionUnsupportedSite other) {
        int byOwner = owner.compareTo(other.owner);
        if (byOwner != 0) {
            return byOwner;
        }
        int byMethod = method.compareTo(other.method);
        if (byMethod != 0) {
            return byMethod;
        }
        int byDescriptor = descriptor.compareTo(other.descriptor);
        if (byDescriptor != 0) {
            return byDescriptor;
        }
        return Integer.compare(instructionIndex, other.instructionIndex);
    }
}
