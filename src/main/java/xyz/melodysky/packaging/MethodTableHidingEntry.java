package xyz.melodysky.packaging;

import java.util.Objects;

/**
 * One registration binding in a build-diverse physical owner layout.
 *
 * <p>The token is hash-only report evidence. It is deliberately not emitted
 * into generated C or the final native binary and is never used as a runtime
 * join key.</p>
 */
public record MethodTableHidingEntry(
        NativeRegistrationEntry registration,
        long token) implements Comparable<MethodTableHidingEntry> {
    public MethodTableHidingEntry {
        Objects.requireNonNull(registration, "registration");
    }

    @Override
    public int compareTo(MethodTableHidingEntry other) {
        int byToken = Long.compareUnsigned(token, other.token);
        return byToken != 0 ? byToken : registration.compareTo(other.registration);
    }
}
