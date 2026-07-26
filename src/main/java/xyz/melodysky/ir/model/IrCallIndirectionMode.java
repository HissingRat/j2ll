package xyz.melodysky.ir.model;

/**
 * IR-level call-indirection representation selected by the protection plan.
 *
 * <p>This is intentionally separate from LLVM call indirection. The IR plan
 * preserves Java call semantics before LLVM lowering, while the LLVM pass only
 * operates on already-lowered native calls.</p>
 */
public enum IrCallIndirectionMode {
    TABLE,
    DISPATCHER
}
