package xyz.melodysky.toolchain.nativetext;

/**
 * Plaintext lifetime allowed for one generated-native text domain.
 */
public enum NativeTextLifetimePolicy {
    /**
     * Decode into an activation-local scratch buffer and clear it on every
     * normal or early function exit.
     */
    CALL_LOCAL_SCRATCH,

    /**
     * Decode once into writable process-lifetime storage.
     *
     * <p>This is only appropriate for low-sensitivity, ordinary runtime
     * diagnostics. JVM class/member/descriptor/reflection/dispatch metadata
     * must never use this lifetime.</p>
     */
    LOW_SENSITIVITY_LAZY_ONCE
}
