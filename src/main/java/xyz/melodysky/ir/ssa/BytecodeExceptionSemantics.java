package xyz.melodysky.ir.ssa;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Classifies bytecode instructions that may transfer to a JVM exception
 * handler before completing normally.
 */
final class BytecodeExceptionSemantics implements Opcodes {
    boolean canRaiseJvmException(AbstractInsnNode instruction) {
        int opcode = instruction.getOpcode();
        if (opcode < 0) {
            return false;
        }
        if (instruction instanceof MethodInsnNode
                || instruction instanceof InvokeDynamicInsnNode
                || instruction instanceof FieldInsnNode
                || instruction instanceof TypeInsnNode
                || instruction instanceof MultiANewArrayInsnNode
                || instruction instanceof LdcInsnNode) {
            return true;
        }
        return SsaOpcodeSemantics.isIntegerDivisionOrRemainder(opcode)
                || SsaOpcodeSemantics.isArrayLoad(opcode)
                || SsaOpcodeSemantics.isArrayStore(opcode)
                || opcode == NEWARRAY
                || opcode == ARRAYLENGTH
                || opcode == ATHROW
                || opcode == MONITORENTER
                || opcode == MONITOREXIT;
    }
}
