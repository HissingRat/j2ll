package xyz.melodysky.packaging;

import java.util.Objects;

public record NativeRegistrationEntry(
        String registrationOwner,
        String methodName,
        String descriptor,
        String nativeSymbol) implements Comparable<NativeRegistrationEntry> {
    public NativeRegistrationEntry {
        Objects.requireNonNull(registrationOwner, "registrationOwner");
        Objects.requireNonNull(methodName, "methodName");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(nativeSymbol, "nativeSymbol");
    }

    @Override
    public int compareTo(NativeRegistrationEntry other) {
        int byOwner = registrationOwner.compareTo(other.registrationOwner);
        if (byOwner != 0) {
            return byOwner;
        }
        int byName = methodName.compareTo(other.methodName);
        return byName != 0 ? byName : descriptor.compareTo(other.descriptor);
    }
}
