package xyz.melodysky.ir.pass.protection;

public enum IrDirectCallResolutionKind {
    BYTECODE_DIRECT,
    DEVIRTUALIZED_SINGLE_TARGET,
    UNRESOLVED,
    MULTIPLE_TARGETS
}
