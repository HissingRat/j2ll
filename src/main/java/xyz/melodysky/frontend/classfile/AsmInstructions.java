package xyz.melodysky.frontend.classfile;

import org.objectweb.asm.tree.AbstractInsnNode;

public final class AsmInstructions {
    private AsmInstructions() {
    }

    public static boolean isExecutable(AbstractInsnNode instruction) {
        int type = instruction.getType();
        return type != AbstractInsnNode.LABEL
                && type != AbstractInsnNode.LINE
                && type != AbstractInsnNode.FRAME;
    }

    public static AbstractInsnNode nextExecutable(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction == null ? null : instruction.getNext();
        while (current != null && !isExecutable(current)) {
            current = current.getNext();
        }
        return current;
    }
}
