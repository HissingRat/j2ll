package xyz.melodysky.frontend.cfg;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.objectweb.asm.tree.AbstractInsnNode;
import xyz.melodysky.frontend.classfile.ParsedMethod;

public record BytecodeCfg(
        ParsedMethod method,
        List<AbstractInsnNode> instructions,
        List<BytecodeBasicBlock> blocks,
        List<BytecodeEdge> edges,
        List<ExceptionRegion> exceptionRegions) {
    public BytecodeCfg {
        Objects.requireNonNull(method, "method");
        instructions = List.copyOf(Objects.requireNonNull(instructions, "instructions"));
        blocks = blocks.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(BytecodeBasicBlock::id))
                .toList();
        edges = edges.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator
                        .comparingInt(BytecodeEdge::fromBlockId)
                        .thenComparingInt(BytecodeEdge::toBlockId)
                        .thenComparing(BytecodeEdge::kind)
                        .thenComparing(edge -> edge.detail() == null ? "" : edge.detail()))
                .toList();
        exceptionRegions = List.copyOf(Objects.requireNonNull(exceptionRegions, "exceptionRegions"));
    }

    public BytecodeBasicBlock entryBlock() {
        return blocks.stream()
                .filter(block -> block.startInstructionIndex() == 0)
                .findFirst()
                .orElseThrow();
    }

    public BytecodeBasicBlock blockByInstructionIndex(int instructionIndex) {
        return blocks.stream()
                .filter(block -> block.startInstructionIndex() <= instructionIndex
                        && instructionIndex < block.endInstructionIndexExclusive())
                .findFirst()
                .orElseThrow();
    }
}
