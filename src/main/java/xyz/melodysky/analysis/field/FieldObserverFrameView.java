package xyz.melodysky.analysis.field;

import java.util.Map;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceValue;

/** Small, bounds-checked view over SourceValue frames. */
final class FieldObserverFrameView {
    private final Frame<SourceValue>[] frames;
    private final Map<AbstractInsnNode, Integer> instructionIndices;

    FieldObserverFrameView(
            Frame<SourceValue>[] frames,
            Map<AbstractInsnNode, Integer> instructionIndices) {
        this.frames = frames;
        this.instructionIndices = instructionIndices;
    }

    InvocationOperands operands(MethodInsnNode call) {
        Frame<SourceValue> frame = frameBefore(call);
        if (frame == null) {
            return null;
        }
        Type[] argumentTypes = Type.getArgumentTypes(call.desc);
        int argumentStart = frame.getStackSize() - argumentTypes.length;
        boolean instance = call.getOpcode() != org.objectweb.asm.Opcodes.INVOKESTATIC;
        int receiverIndex = argumentStart - (instance ? 1 : 0);
        if (argumentStart < 0 || receiverIndex < 0) {
            return null;
        }
        SourceValue[] arguments = new SourceValue[argumentTypes.length];
        for (int index = 0; index < argumentTypes.length; index++) {
            arguments[index] = frame.getStack(argumentStart + index);
        }
        return new InvocationOperands(
                instance ? frame.getStack(receiverIndex) : null,
                arguments,
                argumentTypes);
    }

    SourceValue localBefore(AbstractInsnNode instruction, int local) {
        Frame<SourceValue> frame = frameBefore(instruction);
        return frame == null || local < 0 || local >= frame.getLocals()
                ? null
                : frame.getLocal(local);
    }

    SourceValue stackOperand(AbstractInsnNode instruction, int fromTop) {
        Frame<SourceValue> frame = frameBefore(instruction);
        if (frame == null || fromTop <= 0 || frame.getStackSize() < fromTop) {
            return null;
        }
        return frame.getStack(frame.getStackSize() - fromTop);
    }

    private Frame<SourceValue> frameBefore(AbstractInsnNode instruction) {
        Integer index = instructionIndices.get(instruction);
        return index == null || index < 0 || index >= frames.length
                ? null
                : frames[index];
    }

    record InvocationOperands(
            SourceValue receiver,
            SourceValue[] arguments,
            Type[] argumentTypes) {}
}
