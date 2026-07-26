package xyz.melodysky.packaging;

import java.util.Objects;

/**
 * One runtime registration binding addressed by an opaque, seed-derived token.
 *
 * <p>The Java identity remains available only to the registration source
 * emitter. Native lookup joins the metadata and function-pointer tables by
 * {@link #token()} instead of relying on their physical order.</p>
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
