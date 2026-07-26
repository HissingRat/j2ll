package xyz.melodysky.ir.model;

public enum IrCallInvokeKind {
    STATIC,
    SPECIAL,
    VIRTUAL,
    INTERFACE;

    public static IrCallInvokeKind fromOpcode(IrOpcode opcode) {
        return switch (opcode) {
            case CALL_STATIC -> STATIC;
            case CALL_SPECIAL -> SPECIAL;
            case CALL_VIRTUAL -> VIRTUAL;
            case CALL_INTERFACE -> INTERFACE;
            default -> throw new IllegalArgumentException("opcode does not have an IR direct-call kind: " + opcode);
        };
    }

    public boolean hasReceiver() {
        return this != STATIC;
    }
}
