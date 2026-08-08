package xyz.melodysky.backend.llvm.model;

/**
 * Closed model-level evidence about native stack unwinding.
 *
 * <p>{@link #UNKNOWN} is deliberately the compatibility default. New LLVM
 * producers must opt in to {@link #PROVEN_ABSENT}; forgetting to attach
 * evidence therefore retains unwind information rather than silently
 * weakening exception semantics.</p>
 */
public enum LlvmNativeUnwindSemantics {
    PROVEN_ABSENT,
    REQUIRED,
    UNKNOWN
}
