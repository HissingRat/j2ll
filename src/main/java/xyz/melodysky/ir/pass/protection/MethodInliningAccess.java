package xyz.melodysky.ir.pass.protection;

/**
 * Access proof attached to a method-inlining candidate by program analysis.
 */
public enum MethodInliningAccess {
    STATIC,
    PRIVATE_INSTANCE_SELF
}
