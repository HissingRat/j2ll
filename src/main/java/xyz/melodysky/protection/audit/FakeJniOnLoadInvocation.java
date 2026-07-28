package xyz.melodysky.protection.audit;

/**
 * Adapter implemented by a fake JavaVM/JNIEnv fixture or an external dynamic
 * probe. Calling it represents invoking the final binary's JNI_OnLoad export.
 */
@FunctionalInterface
public interface FakeJniOnLoadInvocation {
    int invoke(FakeJniRegistrationObserver observer);
}
