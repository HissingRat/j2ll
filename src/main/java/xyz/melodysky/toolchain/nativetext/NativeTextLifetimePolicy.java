package xyz.melodysky.toolchain.nativetext;

/**
 * Plaintext lifetime allowed for one generated-native text domain.
 */
public enum NativeTextLifetimePolicy {
    /**
     * Decode into an activation-local scratch buffer and clear it on every
     * normal or early function exit.
     */
    CALL_LOCAL_SCRATCH
}
