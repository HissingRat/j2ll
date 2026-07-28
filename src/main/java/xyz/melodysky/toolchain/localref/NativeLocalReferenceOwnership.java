package xyz.melodysky.toolchain.localref;

import java.util.Objects;
import java.util.Optional;

/**
 * Ownership of one reference-valued SSA handle.
 */
public record NativeLocalReferenceOwnership(
        Kind kind,
        Optional<String> aliasSource) {
    public enum Kind {
        OWNED,
        BORROWED,
        DYNAMIC,
        ALIAS
    }

    public NativeLocalReferenceOwnership {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(aliasSource, "aliasSource");
        if (kind == Kind.ALIAS) {
            if (aliasSource.isEmpty() || aliasSource.orElseThrow().isBlank()) {
                throw new IllegalArgumentException(
                        "alias ownership requires a source value");
            }
        } else if (aliasSource.isPresent()) {
            throw new IllegalArgumentException(
                    "only alias ownership may name a source value");
        }
    }

    public static NativeLocalReferenceOwnership owned() {
        return new NativeLocalReferenceOwnership(Kind.OWNED, Optional.empty());
    }

    public static NativeLocalReferenceOwnership borrowed() {
        return new NativeLocalReferenceOwnership(
                Kind.BORROWED,
                Optional.empty());
    }

    public static NativeLocalReferenceOwnership dynamic() {
        return new NativeLocalReferenceOwnership(
                Kind.DYNAMIC,
                Optional.empty());
    }

    public static NativeLocalReferenceOwnership alias(String sourceValue) {
        return new NativeLocalReferenceOwnership(
                Kind.ALIAS,
                Optional.of(Objects.requireNonNull(
                        sourceValue,
                        "sourceValue")));
    }
}
