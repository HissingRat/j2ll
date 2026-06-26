package xyz.melodysky.analysis.callgraph;

import org.objectweb.asm.Opcodes;

public enum InvokeKind {
    STATIC,
    SPECIAL,
    VIRTUAL,
    INTERFACE,
    DYNAMIC;

    public static InvokeKind fromOpcode(int opcode) {
        return switch (opcode) {
            case Opcodes.INVOKESTATIC -> STATIC;
            case Opcodes.INVOKESPECIAL -> SPECIAL;
            case Opcodes.INVOKEVIRTUAL -> VIRTUAL;
            case Opcodes.INVOKEINTERFACE -> INTERFACE;
            case Opcodes.INVOKEDYNAMIC -> DYNAMIC;
            default -> throw new IllegalArgumentException("not an invoke opcode: " + opcode);
        };
    }

    public boolean dispatchesDynamically() {
        return this == VIRTUAL || this == INTERFACE || this == DYNAMIC;
    }
}
