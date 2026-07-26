package xyz.melodysky.analysis.field;

import java.util.Objects;

public record FieldAccessSite(
        FieldId field,
        String methodKey,
        String methodOwner,
        String methodName,
        boolean methodStatic,
        FieldCodeOrigin origin,
        FieldReferenceKind referenceKind,
        String symbolicOwner,
        int instructionIndex,
        boolean bootstrapArgument) implements Comparable<FieldAccessSite> {
    public FieldAccessSite {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(methodKey, "methodKey");
        Objects.requireNonNull(methodOwner, "methodOwner");
        Objects.requireNonNull(methodName, "methodName");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(referenceKind, "referenceKind");
        Objects.requireNonNull(symbolicOwner, "symbolicOwner");
        if (instructionIndex < 0) {
            throw new IllegalArgumentException("instructionIndex must be non-negative");
        }
    }

    @Override
    public int compareTo(FieldAccessSite other) {
        int byMethod = methodKey.compareTo(other.methodKey);
        if (byMethod != 0) {
            return byMethod;
        }
        int byIndex = Integer.compare(instructionIndex, other.instructionIndex);
        if (byIndex != 0) {
            return byIndex;
        }
        int byKind = referenceKind.compareTo(other.referenceKind);
        if (byKind != 0) {
            return byKind;
        }
        return symbolicOwner.compareTo(other.symbolicOwner);
    }
}
