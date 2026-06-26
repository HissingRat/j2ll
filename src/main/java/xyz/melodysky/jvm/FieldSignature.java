package xyz.melodysky.jvm;

import java.util.Objects;

public record FieldSignature(String name, String descriptor) implements Comparable<FieldSignature> {
    public FieldSignature {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");
        if (name.isBlank() || descriptor.isBlank()) {
            throw new IllegalArgumentException("field signature parts must not be blank");
        }
    }

    @Override
    public int compareTo(FieldSignature other) {
        int byName = name.compareTo(other.name);
        return byName != 0 ? byName : descriptor.compareTo(other.descriptor);
    }

    @Override
    public String toString() {
        return name + "!" + descriptor;
    }
}
