package xyz.melodysky.ir.pass.protection;

import java.util.Objects;

public record IrCallIndirectionTarget(
        String entryId,
        String targetMethodKey,
        int indexOrSelector) {
    public IrCallIndirectionTarget {
        requireText(entryId, "entryId");
        requireText(targetMethodKey, "targetMethodKey");
        if (indexOrSelector < 0) {
            throw new IllegalArgumentException("indexOrSelector must be non-negative");
        }
    }

    private static void requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
