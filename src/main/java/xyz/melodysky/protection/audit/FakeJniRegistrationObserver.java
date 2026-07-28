package xyz.melodysky.protection.audit;

import java.util.List;

/**
 * Narrow fake-JNIEnv surface exposed only while a dynamic JNI_OnLoad probe is
 * running.
 */
@FunctionalInterface
public interface FakeJniRegistrationObserver {
    void registerNatives(
            String ownerInternalName,
            List<ObservedNativeBinding> bindings);
}
