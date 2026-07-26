package xyz.melodysky.analysis.field;

import java.util.Optional;

/**
 * Exact JVM storage semantics for an internalized static field.
 *
 * <p>The SSA model intentionally collapses {@code boolean}, {@code byte},
 * {@code short}, {@code char}, and {@code int} to one integer type. Field
 * analysis must retain the declaration descriptor because writes apply
 * descriptor-specific narrowing.</p>
 */
public enum NativeFieldStorageKind {
    BOOLEAN("z"),
    BYTE("b"),
    SHORT("s"),
    CHAR("c"),
    INT("i"),
    LONG("j"),
    FLOAT("f"),
    DOUBLE("d"),
    REFERENCE("r");

    private final String wireName;

    NativeFieldStorageKind(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public boolean reference() {
        return this == REFERENCE;
    }

    public static Optional<NativeFieldStorageKind> fromDescriptor(String descriptor) {
        if (descriptor == null || descriptor.isEmpty()) {
            return Optional.empty();
        }
        return switch (descriptor.charAt(0)) {
            case 'Z' -> exact(descriptor, BOOLEAN);
            case 'B' -> exact(descriptor, BYTE);
            case 'S' -> exact(descriptor, SHORT);
            case 'C' -> exact(descriptor, CHAR);
            case 'I' -> exact(descriptor, INT);
            case 'J' -> exact(descriptor, LONG);
            case 'F' -> exact(descriptor, FLOAT);
            case 'D' -> exact(descriptor, DOUBLE);
            case 'L' -> descriptor.endsWith(";") ? Optional.of(REFERENCE) : Optional.empty();
            case '[' -> Optional.of(REFERENCE);
            default -> Optional.empty();
        };
    }

    public static Optional<NativeFieldStorageKind> fromWireName(String wireName) {
        for (NativeFieldStorageKind kind : values()) {
            if (kind.wireName.equals(wireName)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }

    private static Optional<NativeFieldStorageKind> exact(
            String descriptor,
            NativeFieldStorageKind kind) {
        return descriptor.length() == 1 ? Optional.of(kind) : Optional.empty();
    }
}
