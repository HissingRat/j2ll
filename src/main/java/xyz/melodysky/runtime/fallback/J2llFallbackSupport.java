package xyz.melodysky.runtime.fallback;

import java.lang.invoke.MethodHandles;
import java.util.Objects;

public final class J2llFallbackSupport {
    private J2llFallbackSupport() {
    }

    public static Class<?> defineHiddenFallback(Class<?> owner, byte[] classBytes) throws IllegalAccessException {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(classBytes, "classBytes");
        MethodHandles.Lookup ownerLookup = MethodHandles.privateLookupIn(owner, MethodHandles.lookup());
        return ownerLookup
                .defineHiddenClass(classBytes, true, MethodHandles.Lookup.ClassOption.NESTMATE)
                .lookupClass();
    }
}
