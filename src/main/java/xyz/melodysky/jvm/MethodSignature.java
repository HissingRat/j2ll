package xyz.melodysky.jvm;

import java.util.Objects;

public record MethodSignature(String name, String descriptor) implements Comparable<MethodSignature> {
    public MethodSignature {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");
        if (name.isBlank() || descriptor.isBlank()) {
            throw new IllegalArgumentException("method signature parts must not be blank");
        }
    }

    @Override
    public int compareTo(MethodSignature other) {
        int byName = name.compareTo(other.name);
        return byName != 0 ? byName : descriptor.compareTo(other.descriptor);
    }

    @Override
    public String toString() {
        return name + "!" + descriptor;
    }
}
