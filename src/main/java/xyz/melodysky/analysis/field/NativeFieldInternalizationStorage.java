package xyz.melodysky.analysis.field;

/**
 * Physical representation selected for one field decision.
 *
 * <p>{@link #NATIVE_SLOT} is the mutable descriptor-aware sidecar/native
 * storage path. {@link #COMPILE_TIME_CONSTANT} has no runtime storage: every
 * approved read is replaced in SSA and the declaration is removed only after
 * the final native plan is revalidated.</p>
 */
public enum NativeFieldInternalizationStorage {
    JVM_FIELD,
    NATIVE_SLOT,
    COMPILE_TIME_CONSTANT
}
