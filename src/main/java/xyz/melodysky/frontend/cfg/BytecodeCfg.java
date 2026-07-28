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
                .sorted(BytecodeCfg::compareEdges)
                .toList();
        exceptionRegions = List.copyOf(Objects.requireNonNull(exceptionRegions, "exceptionRegions"));
    }

    private static int compareEdges(BytecodeEdge left, BytecodeEdge right) {
        int sourceOrder = Integer.compare(left.fromBlockId(), right.fromBlockId());
        if (sourceOrder != 0) {
            return sourceOrder;
        }
        boolean leftException = left.kind() == BytecodeEdgeKind.EXCEPTION;
        boolean rightException = right.kind() == BytecodeEdgeKind.EXCEPTION;
        if (leftException || rightException) {
            if (leftException && rightException) {
                // Stream sorting is stable. Equal exception edges therefore retain
                // their JVM exception-table order, which defines first-match catch
                // semantics even when handler blocks have a different layout.
                return 0;
            }
            return leftException ? 1 : -1;
        }
        int targetOrder = Integer.compare(left.toBlockId(), right.toBlockId());
        if (targetOrder != 0) {
            return targetOrder;
        }
        int kindOrder = left.kind().compareTo(right.kind());
        if (kindOrder != 0) {
            return kindOrder;
        }
        String leftDetail = left.detail() == null ? "" : left.detail();
        String rightDetail = right.detail() == null ? "" : right.detail();
        return leftDetail.compareTo(rightDetail);
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
