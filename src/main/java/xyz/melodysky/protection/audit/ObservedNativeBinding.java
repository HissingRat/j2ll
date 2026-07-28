package xyz.melodysky.protection.audit;

import java.util.Objects;

/**
 * One binding observed by a fake JNIEnv {@code RegisterNatives} callback.
 *
 * <p>The raw values live only for the dynamic probe invocation. The resulting
 * metric stores domain-separated hashes.
 */
public record ObservedNativeBinding(
        String methodName,
        String descriptor,
        String functionIdentity) {
    public ObservedNativeBinding {
        Objects.requireNonNull(methodName, "methodName");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(functionIdentity, "functionIdentity");
        if (methodName.isBlank()
                || descriptor.isBlank()
                || functionIdentity.isBlank()) {
            throw new IllegalArgumentException(
                    "observed native binding values must not be blank");
        }
    }
}
