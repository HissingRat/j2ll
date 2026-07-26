package xyz.melodysky.analysis.field;

import java.util.Objects;

public record FieldDynamicBoundary(
        FieldDynamicBoundaryKind kind,
        String methodOwner,
        String methodKey,
        String detail) implements Comparable<FieldDynamicBoundary> {
    public FieldDynamicBoundary {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(methodOwner, "methodOwner");
        Objects.requireNonNull(methodKey, "methodKey");
        Objects.requireNonNull(detail, "detail");
    }

    @Override
    public int compareTo(FieldDynamicBoundary other) {
        int byKind = kind.compareTo(other.kind);
        if (byKind != 0) {
            return byKind;
        }
        int byOwner = methodOwner.compareTo(other.methodOwner);
        if (byOwner != 0) {
            return byOwner;
        }
        int byMethod = methodKey.compareTo(other.methodKey);
        return byMethod != 0 ? byMethod : detail.compareTo(other.detail);
    }
}
