package xyz.melodysky.analysis.field;

import java.util.Objects;

public record UnresolvedFieldReference(
        String symbolicOwner,
        String name,
        String descriptor,
        String methodKey,
        FieldReferenceKind referenceKind,
        int instructionIndex) implements Comparable<UnresolvedFieldReference> {
    public UnresolvedFieldReference {
        Objects.requireNonNull(symbolicOwner, "symbolicOwner");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(methodKey, "methodKey");
        Objects.requireNonNull(referenceKind, "referenceKind");
    }

    @Override
    public int compareTo(UnresolvedFieldReference other) {
        return stableKey().compareTo(other.stableKey());
    }

    private String stableKey() {
        return methodKey + ":" + instructionIndex + ":" + symbolicOwner + "#" + name + "!" + descriptor + ":" + referenceKind;
    }
}
