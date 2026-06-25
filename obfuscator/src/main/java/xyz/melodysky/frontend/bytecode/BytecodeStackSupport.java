package xyz.melodysky.frontend.bytecode;

import org.objectweb.asm.tree.MethodNode;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;

import java.util.Deque;

final class BytecodeStackSupport {
    private BytecodeStackSupport() {
    }

    static IrValue popIntLike(Deque<IrValue> stack, MethodNode methodNode) {
        if (stack.isEmpty()) {
            throw new UnsupportedBytecodeException("Operand stack underflow in " + methodNode.name + methodNode.desc);
        }
        IrValue value = stack.pop();
        if (!BytecodeTypeSupport.isIntLike(value.type())) {
            throw new UnsupportedBytecodeException("Expected int-like value on operand stack in " + methodNode.name
                    + methodNode.desc + " but found " + value.type().displayName());
        }
        return value;
    }

    static IrValue popReferenceLike(Deque<IrValue> stack, MethodNode methodNode) {
        if (stack.isEmpty()) {
            throw new UnsupportedBytecodeException("Operand stack underflow in " + methodNode.name + methodNode.desc);
        }
        IrValue value = stack.pop();
        if (value.type().isPrimitive() || value.type() == IrType.VOID) {
            throw new UnsupportedBytecodeException("Expected reference-like value on operand stack in "
                    + methodNode.name + methodNode.desc + " but found " + value.type().displayName());
        }
        return value;
    }

    static IrValue popExactType(Deque<IrValue> stack, MethodNode methodNode, IrType expectedType) {
        if (stack.isEmpty()) {
            throw new UnsupportedBytecodeException("Operand stack underflow in " + methodNode.name + methodNode.desc);
        }
        IrValue value = stack.pop();
        if (value.type() != expectedType) {
            throw new UnsupportedBytecodeException("Expected " + expectedType.displayName() + " on operand stack in "
                    + methodNode.name + methodNode.desc + " but found " + value.type().displayName());
        }
        return value;
    }
}
