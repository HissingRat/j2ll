package xyz.melodysky.frontend.bytecode;

import org.objectweb.asm.tree.MethodNode;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrValue;

import java.util.Deque;
import java.util.List;

final class BytecodeLoweringContext {
    private final String ownerInternalName;
    private final MethodNode methodNode;
    private final List<IrInstruction> currentInstructions;
    private final Deque<IrValue> stack;
    private int nextValueId;

    BytecodeLoweringContext(String ownerInternalName, MethodNode methodNode,
                            List<IrInstruction> currentInstructions, Deque<IrValue> stack,
                            int nextValueId) {
        this.ownerInternalName = ownerInternalName;
        this.methodNode = methodNode;
        this.currentInstructions = currentInstructions;
        this.stack = stack;
        this.nextValueId = nextValueId;
    }

    String ownerInternalName() {
        return ownerInternalName;
    }

    MethodNode methodNode() {
        return methodNode;
    }

    List<IrInstruction> currentInstructions() {
        return currentInstructions;
    }

    Deque<IrValue> stack() {
        return stack;
    }

    int nextValueId() {
        return nextValueId;
    }

    void setNextValueId(int nextValueId) {
        this.nextValueId = nextValueId;
    }

    IrValue allocateValue(xyz.melodysky.ir.model.IrType type, String debugName) {
        return new IrValue(nextValueId++, type, debugName);
    }
}
