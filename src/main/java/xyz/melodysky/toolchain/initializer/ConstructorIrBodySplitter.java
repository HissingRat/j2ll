package xyz.melodysky.toolchain.initializer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;
import xyz.melodysky.frontend.cfg.MethodCfgBuilder;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.ssa.BytecodeToSsaLowerer;
import xyz.melodysky.ir.validate.IrMethodValidator;
import xyz.melodysky.packaging.MethodRewriteDecision;

/**
 * Removes the Java-resident constructor prefix from raw SSA.
 *
 * <p>The prefix is lowered independently from an exact bytecode clone ending
 * immediately after the initializing invocation. Matching the resulting SSA
 * prefix handles JVM intrinsics such as {@code Object.<init>()}, which are
 * intentionally absent from the full method IR, as well as compiler-generated
 * captured-field stores that must remain on the Java side.</p>
 */
final class ConstructorIrBodySplitter implements Opcodes {
    Optional<IrMethod> split(
            MethodRewriteDecision decision,
            IrMethod source,
            ConstructorPrefixPlan prefix) {
        Optional<List<IrInstruction>> maybePrefixInstructions =
                lowerPrefix(decision.method(), prefix);
        if (maybePrefixInstructions.isEmpty() || source.blocks().isEmpty()) {
            return Optional.empty();
        }
        List<IrInstruction> prefixInstructions = maybePrefixInstructions.orElseThrow();
        IrBlock entry = source.blocks().get(0);
        if (entry.instructions().size() < prefixInstructions.size()
                || !entry.instructions()
                        .subList(0, prefixInstructions.size())
                        .equals(prefixInstructions)) {
            return Optional.empty();
        }

        ArrayList<IrBlock> blocks = new ArrayList<>(source.blocks());
        blocks.set(0, new IrBlock(
                entry.name(),
                entry.parameters(),
                entry.exceptionCatchTypes(),
                entry.exceptionEdges(),
                entry.instructions().subList(prefixInstructions.size(), entry.instructions().size()),
                entry.terminator()));
        IrMethod nativeBody = new IrMethod(
                source.owner(),
                source.name(),
                source.descriptor(),
                source.returnType(),
                source.parameters(),
                blocks);
        return new IrMethodValidator().validate(nativeBody).isEmpty()
                ? Optional.of(nativeBody)
                : Optional.empty();
    }

    private Optional<List<IrInstruction>> lowerPrefix(
            ParsedMethod source,
            ConstructorPrefixPlan prefix) {
        MethodNode method = cloneMethod(source.methodNode());
        AbstractInsnNode boundary = opcodeInstructionAt(
                method.instructions.getFirst(),
                prefix.initializationOpcodeIndex());
        if (boundary == null) {
            return Optional.empty();
        }
        for (AbstractInsnNode instruction = boundary.getNext(); instruction != null; ) {
            AbstractInsnNode next = instruction.getNext();
            method.instructions.remove(instruction);
            instruction = next;
        }
        method.instructions.add(new InsnNode(RETURN));
        method.tryCatchBlocks.clear();
        if (method.localVariables != null) {
            method.localVariables.clear();
        }

        ParsedMethod prefixMethod = new ParsedMethod(
                source.owner(),
                source.name(),
                source.descriptor(),
                source.accessFlags(),
                source.ownerFields(),
                source.exceptions(),
                List.of(),
                true,
                source.maxLocals(),
                source.maxStack(),
                method);
        var cfg = new MethodCfgBuilder().build(prefixMethod);
        if (cfg.artifact().isEmpty()) {
            return Optional.empty();
        }
        var ssa = new BytecodeToSsaLowerer().lower(cfg.artifact().orElseThrow());
        if (ssa.artifact().isEmpty()
                || ssa.artifact().orElseThrow().irMethod().isEmpty()) {
            return Optional.empty();
        }
        IrMethod prefixIr = ssa.artifact().orElseThrow().irMethod().orElseThrow();
        if (prefixIr.blocks().size() != 1) {
            return Optional.empty();
        }
        return Optional.of(prefixIr.blocks().get(0).instructions());
    }

    private MethodNode cloneMethod(MethodNode source) {
        MethodNode clone = new MethodNode(
                ASM9,
                source.access,
                source.name,
                source.desc,
                source.signature,
                source.exceptions == null ? null : source.exceptions.toArray(String[]::new));
        source.accept(clone);
        return clone;
    }

    private AbstractInsnNode opcodeInstructionAt(
            AbstractInsnNode first,
            int wantedOpcodeIndex) {
        int opcodeIndex = -1;
        for (AbstractInsnNode instruction = first;
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction.getOpcode() < 0) {
                continue;
            }
            opcodeIndex++;
            if (opcodeIndex == wantedOpcodeIndex) {
                return instruction;
            }
        }
        return null;
    }
}
