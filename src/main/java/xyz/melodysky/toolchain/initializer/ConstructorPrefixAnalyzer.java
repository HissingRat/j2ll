package xyz.melodysky.toolchain.initializer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;
import xyz.melodysky.packaging.MethodRewriteDecision;

/**
 * Locates the verifier-required {@code this(...)} or {@code super(...)}
 * invocation in a constructor.
 *
 * <p>The receiver is identified from ASM source frames rather than from a
 * method-owner heuristic. This avoids confusing a constructor invocation used
 * to compute a super-constructor argument with the invocation that initializes
 * {@code uninitializedThis}.</p>
 */
final class ConstructorPrefixAnalyzer implements Opcodes {
    Optional<ConstructorPrefixPlan> analyze(MethodRewriteDecision decision) {
        if (!decision.method().name().equals("<init>")
                || !decision.method().exceptionHandlers().isEmpty()
                || !decision.method().methodNode().tryCatchBlocks.isEmpty()) {
            return Optional.empty();
        }

        Frame<SourceValue>[] frames;
        try {
            frames = new Analyzer<>(new SourceInterpreter())
                    .analyze(decision.method().owner(), decision.method().methodNode());
        } catch (AnalyzerException | RuntimeException exception) {
            return Optional.empty();
        }

        ArrayList<Candidate> candidates = new ArrayList<>();
        int opcodeIndex = -1;
        int instructionIndex = -1;
        for (AbstractInsnNode instruction = decision.method().methodNode().instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            instructionIndex++;
            if (instruction.getOpcode() < 0) {
                continue;
            }
            opcodeIndex++;
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != INVOKESPECIAL
                    || !call.name.equals("<init>")) {
                continue;
            }
            Frame<SourceValue> frame = frames[instructionIndex];
            if (frame != null && receiverComesFromLocalZero(frame, call)) {
                candidates.add(new Candidate(opcodeIndex, call));
            }
        }
        if (candidates.size() != 1) {
            return Optional.empty();
        }
        Candidate initialization = candidates.get(0);
        if (!hasLinearPrefix(decision, initialization.opcodeIndex())) {
            return Optional.empty();
        }
        return Optional.of(new ConstructorPrefixPlan(
                initialization.opcodeIndex(),
                initialization.call().owner,
                initialization.call().desc,
                initialization.call().itf));
    }

    private boolean receiverComesFromLocalZero(
            Frame<SourceValue> frame,
            MethodInsnNode call) {
        int receiverIndex = frame.getStackSize() - Type.getArgumentTypes(call.desc).length - 1;
        if (receiverIndex < 0) {
            return false;
        }
        SourceValue receiver = frame.getStack(receiverIndex);
        return receiver.insns.stream().anyMatch(instruction ->
                instruction instanceof VarInsnNode variable
                        && variable.getOpcode() == ALOAD
                        && variable.var == 0);
    }

    private boolean hasLinearPrefix(
            MethodRewriteDecision decision,
            int initializationOpcodeIndex) {
        int opcodeIndex = -1;
        for (AbstractInsnNode instruction = decision.method().methodNode().instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction.getOpcode() < 0) {
                continue;
            }
            opcodeIndex++;
            if (opcodeIndex > initializationOpcodeIndex) {
                return true;
            }
            if (instruction instanceof JumpInsnNode
                    || instruction instanceof TableSwitchInsnNode
                    || instruction instanceof LookupSwitchInsnNode
                    || isExit(instruction.getOpcode())) {
                return false;
            }
        }
        return opcodeIndex == initializationOpcodeIndex;
    }

    private boolean isExit(int opcode) {
        return List.of(IRETURN, LRETURN, FRETURN, DRETURN, ARETURN, RETURN, ATHROW, RET)
                .contains(opcode);
    }

    private record Candidate(int opcodeIndex, MethodInsnNode call) {
    }
}
