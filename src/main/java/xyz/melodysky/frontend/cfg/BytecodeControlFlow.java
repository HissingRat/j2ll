package xyz.melodysky.frontend.cfg;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;

public final class BytecodeControlFlow implements Opcodes {
    private BytecodeControlFlow() {
    }

    public static boolean isReturn(int opcode) {
        return opcode >= IRETURN && opcode <= RETURN;
    }

    public static boolean isSwitch(AbstractInsnNode instruction) {
        return instruction.getType() == AbstractInsnNode.TABLESWITCH_INSN
                || instruction.getType() == AbstractInsnNode.LOOKUPSWITCH_INSN;
    }

    public static boolean isUnconditionalBranch(int opcode) {
        return opcode == GOTO || opcode == JSR || opcode == RET;
    }

    public static boolean isConditionalBranch(int opcode) {
        return (opcode >= IFEQ && opcode <= IF_ACMPNE) || opcode == IFNULL || opcode == IFNONNULL;
    }

    public static boolean isTerminator(AbstractInsnNode instruction) {
        int opcode = instruction.getOpcode();
        return isReturn(opcode)
                || opcode == ATHROW
                || isUnconditionalBranch(opcode)
                || isSwitch(instruction);
    }
}
