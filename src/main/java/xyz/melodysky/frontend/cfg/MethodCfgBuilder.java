package xyz.melodysky.frontend.cfg;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticLocation;
import xyz.melodysky.diagnostic.DiagnosticStage;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.pipeline.StageResult;

public final class MethodCfgBuilder implements Opcodes {
    public StageResult<MethodCfgResult> build(ParsedMethod method) {
        if (!method.hasCode()) {
            Diagnostic diagnostic = Diagnostic.info(
                            DiagnosticStage.CFG,
                            CfgDiagnostics.METHOD_HAS_NO_CODE,
                            "method has no Code attribute and does not produce a CFG")
                    .at(DiagnosticLocation.methodLocation(method.owner(), method.name(), method.descriptor()))
                    .withDecision("notApplicable");
            return StageResult.complete(
                    DiagnosticStage.CFG,
                    MethodCfgResult.noCode(method, "NO_CODE", "method has no Code attribute"),
                    List.of(diagnostic));
        }

        InstructionIndexer indexer = InstructionIndexer.from(method.methodNode().instructions);
        if (indexer.executableInstructions().isEmpty()) {
            Diagnostic diagnostic = Diagnostic.error(
                            DiagnosticStage.CFG,
                            CfgDiagnostics.CFG_MISSING_ENTRY,
                            "method has Code metadata but no executable entry instruction")
                    .at(DiagnosticLocation.methodLocation(method.owner(), method.name(), method.descriptor()));
            return StageResult.failed(DiagnosticStage.CFG, List.of(diagnostic));
        }

        TreeSet<Integer> starts = collectBlockStarts(method, indexer);
        List<BytecodeBasicBlock> blocks = buildBlocks(starts, indexer.executableInstructions(), method);
        Map<Integer, Integer> instructionToBlock = instructionToBlock(blocks);
        List<ExceptionRegion> regions = buildExceptionRegions(method, indexer);
        List<BytecodeEdge> edges = buildNormalEdges(blocks, indexer.executableInstructions(), instructionToBlock);
        edges = withExceptionEdges(edges, blocks, regions, instructionToBlock);
        List<BytecodeBasicBlock> reachableBlocks = markReachable(blocks, edges);

        BytecodeCfg cfg = new BytecodeCfg(method, indexer.executableInstructions(), reachableBlocks, edges, regions);
        return StageResult.complete(DiagnosticStage.CFG, MethodCfgResult.built(cfg));
    }

    private TreeSet<Integer> collectBlockStarts(ParsedMethod method, InstructionIndexer indexer) {
        TreeSet<Integer> starts = new TreeSet<>();
        starts.add(0);

        List<AbstractInsnNode> instructions = indexer.executableInstructions();
        for (int index = 0; index < instructions.size(); index++) {
            AbstractInsnNode instruction = instructions.get(index);
            if (instruction instanceof JumpInsnNode jump) {
                addLabelTarget(starts, indexer, jump.label);
                if (BytecodeControlFlow.isConditionalBranch(instruction.getOpcode())) {
                    addIfPresent(starts, index + 1, instructions.size());
                }
            } else if (instruction instanceof TableSwitchInsnNode tableSwitch) {
                addLabelTarget(starts, indexer, tableSwitch.dflt);
                for (LabelNode label : tableSwitch.labels) {
                    addLabelTarget(starts, indexer, label);
                }
            } else if (instruction instanceof LookupSwitchInsnNode lookupSwitch) {
                addLabelTarget(starts, indexer, lookupSwitch.dflt);
                for (LabelNode label : lookupSwitch.labels) {
                    addLabelTarget(starts, indexer, label);
                }
            }

            if (BytecodeControlFlow.isTerminator(instruction)) {
                addIfPresent(starts, index + 1, instructions.size());
            }
        }

        for (TryCatchBlockNode handler : method.methodNode().tryCatchBlocks) {
            addLabelTarget(starts, indexer, handler.start);
            addLabelTarget(starts, indexer, handler.end);
            addLabelTarget(starts, indexer, handler.handler);
        }

        return starts;
    }

    private void addLabelTarget(Set<Integer> starts, InstructionIndexer indexer, LabelNode label) {
        Integer target = indexer.indexOf(label);
        if (target != null) {
            starts.add(target);
        }
    }

    private void addIfPresent(Set<Integer> starts, int index, int instructionCount) {
        if (index >= 0 && index < instructionCount) {
            starts.add(index);
        }
    }

    private List<BytecodeBasicBlock> buildBlocks(
            TreeSet<Integer> starts,
            List<AbstractInsnNode> instructions,
            ParsedMethod method) {
        ArrayList<Integer> sortedStarts = new ArrayList<>(starts);
        HashMap<Integer, List<String>> handlerCatchTypes = handlerCatchTypes(method, starts);
        ArrayList<BytecodeBasicBlock> blocks = new ArrayList<>();
        for (int ordinal = 0; ordinal < sortedStarts.size(); ordinal++) {
            int start = sortedStarts.get(ordinal);
            int nextStart = ordinal + 1 < sortedStarts.size() ? sortedStarts.get(ordinal + 1) : instructions.size();
            int end = nextStart;
            for (int index = start; index < nextStart; index++) {
                if (BytecodeControlFlow.isTerminator(instructions.get(index))) {
                    end = index + 1;
                    break;
                }
            }
            blocks.add(new BytecodeBasicBlock(
                    blocks.size(),
                    start,
                    end,
                    false,
                    handlerCatchTypes.getOrDefault(start, List.of())));
        }
        return blocks;
    }

    private HashMap<Integer, List<String>> handlerCatchTypes(ParsedMethod method, Set<Integer> starts) {
        HashMap<Integer, List<String>> catchTypes = new HashMap<>();
        InstructionIndexer indexer = InstructionIndexer.from(method.methodNode().instructions);
        for (TryCatchBlockNode handler : method.methodNode().tryCatchBlocks) {
            Integer handlerIndex = indexer.indexOf(handler.handler);
            if (handlerIndex == null || !starts.contains(handlerIndex)) {
                continue;
            }
            catchTypes.computeIfAbsent(handlerIndex, ignored -> new ArrayList<>())
                    .add(handler.type == null ? ExceptionRegion.CATCH_ALL : handler.type);
        }
        return catchTypes;
    }

    private Map<Integer, Integer> instructionToBlock(List<BytecodeBasicBlock> blocks) {
        HashMap<Integer, Integer> mapping = new HashMap<>();
        for (BytecodeBasicBlock block : blocks) {
            for (int index = block.startInstructionIndex(); index < block.endInstructionIndexExclusive(); index++) {
                mapping.put(index, block.id());
            }
        }
        return mapping;
    }

    private List<ExceptionRegion> buildExceptionRegions(ParsedMethod method, InstructionIndexer indexer) {
        ArrayList<ExceptionRegion> regions = new ArrayList<>();
        for (TryCatchBlockNode handler : method.methodNode().tryCatchBlocks) {
            Integer start = indexer.indexOf(handler.start);
            Integer end = indexer.indexOf(handler.end);
            Integer target = indexer.indexOf(handler.handler);
            if (start == null || target == null) {
                continue;
            }
            int endExclusive = end == null ? indexer.executableInstructions().size() : end;
            regions.add(new ExceptionRegion(start, endExclusive, target, handler.type));
        }
        return regions;
    }

    private List<BytecodeEdge> buildNormalEdges(
            List<BytecodeBasicBlock> blocks,
            List<AbstractInsnNode> instructions,
            Map<Integer, Integer> instructionToBlock) {
        LinkedHashSet<BytecodeEdge> edges = new LinkedHashSet<>();
        for (BytecodeBasicBlock block : blocks) {
            if (block.startInstructionIndex() == block.endInstructionIndexExclusive()) {
                continue;
            }
            AbstractInsnNode terminator = instructions.get(block.endInstructionIndexExclusive() - 1);
            int opcode = terminator.getOpcode();
            if (terminator instanceof JumpInsnNode jump) {
                addTargetEdge(edges, block.id(), BytecodeEdgeKind.BRANCH, jump.label, instructions, instructionToBlock, null);
                if (BytecodeControlFlow.isConditionalBranch(opcode)) {
                    addBlockEdgeAtInstruction(edges, block.id(), block.endInstructionIndexExclusive(), BytecodeEdgeKind.FALLTHROUGH, instructionToBlock, null);
                }
            } else if (terminator instanceof TableSwitchInsnNode tableSwitch) {
                addTargetEdge(edges, block.id(), BytecodeEdgeKind.SWITCH, tableSwitch.dflt, instructions, instructionToBlock, "default");
                for (int i = 0; i < tableSwitch.labels.size(); i++) {
                    int key = tableSwitch.min + i;
                    addTargetEdge(edges, block.id(), BytecodeEdgeKind.SWITCH, tableSwitch.labels.get(i), instructions, instructionToBlock, Integer.toString(key));
                }
            } else if (terminator instanceof LookupSwitchInsnNode lookupSwitch) {
                addTargetEdge(edges, block.id(), BytecodeEdgeKind.SWITCH, lookupSwitch.dflt, instructions, instructionToBlock, "default");
                for (int i = 0; i < lookupSwitch.labels.size(); i++) {
                    addTargetEdge(edges, block.id(), BytecodeEdgeKind.SWITCH, lookupSwitch.labels.get(i), instructions, instructionToBlock, lookupSwitch.keys.get(i).toString());
                }
            } else if (!BytecodeControlFlow.isTerminator(terminator)) {
                addBlockEdgeAtInstruction(edges, block.id(), block.endInstructionIndexExclusive(), BytecodeEdgeKind.FALLTHROUGH, instructionToBlock, null);
            }
        }
        return List.copyOf(edges);
    }

    private void addTargetEdge(
            Set<BytecodeEdge> edges,
            int fromBlockId,
            BytecodeEdgeKind kind,
            LabelNode target,
            List<AbstractInsnNode> instructions,
            Map<Integer, Integer> instructionToBlock,
            String detail) {
        AbstractInsnNode current = target;
        while (current != null && !instructions.contains(current)) {
            current = current.getNext();
        }
        if (current == null) {
            return;
        }
        int targetIndex = instructions.indexOf(current);
        addBlockEdgeAtInstruction(edges, fromBlockId, targetIndex, kind, instructionToBlock, detail);
    }

    private void addBlockEdgeAtInstruction(
            Set<BytecodeEdge> edges,
            int fromBlockId,
            int instructionIndex,
            BytecodeEdgeKind kind,
            Map<Integer, Integer> instructionToBlock,
            String detail) {
        Integer targetBlock = instructionToBlock.get(instructionIndex);
        if (targetBlock != null) {
            edges.add(new BytecodeEdge(fromBlockId, targetBlock, kind, detail));
        }
    }

    private List<BytecodeEdge> withExceptionEdges(
            List<BytecodeEdge> edges,
            List<BytecodeBasicBlock> blocks,
            List<ExceptionRegion> regions,
            Map<Integer, Integer> instructionToBlock) {
        LinkedHashSet<BytecodeEdge> allEdges = new LinkedHashSet<>(edges);
        for (ExceptionRegion region : regions) {
            Integer handlerBlock = instructionToBlock.get(region.handlerInstructionIndex());
            if (handlerBlock == null) {
                continue;
            }
            for (BytecodeBasicBlock block : blocks) {
                if (rangesIntersect(
                        block.startInstructionIndex(),
                        block.endInstructionIndexExclusive(),
                        region.startInstructionIndex(),
                        region.endInstructionIndexExclusive())) {
                    allEdges.add(new BytecodeEdge(block.id(), handlerBlock, BytecodeEdgeKind.EXCEPTION, region.catchType()));
                }
            }
        }
        return List.copyOf(allEdges);
    }

    private boolean rangesIntersect(int aStart, int aEnd, int bStart, int bEnd) {
        return aStart < bEnd && bStart < aEnd;
    }

    private List<BytecodeBasicBlock> markReachable(List<BytecodeBasicBlock> blocks, List<BytecodeEdge> edges) {
        HashMap<Integer, List<Integer>> successors = new HashMap<>();
        for (BytecodeEdge edge : edges) {
            successors.computeIfAbsent(edge.fromBlockId(), ignored -> new ArrayList<>()).add(edge.toBlockId());
        }

        HashSet<Integer> reachable = new HashSet<>();
        ArrayDeque<Integer> work = new ArrayDeque<>();
        if (!blocks.isEmpty()) {
            reachable.add(0);
            work.add(0);
        }
        while (!work.isEmpty()) {
            int blockId = work.removeFirst();
            for (int successor : successors.getOrDefault(blockId, List.of())) {
                if (reachable.add(successor)) {
                    work.addLast(successor);
                }
            }
        }

        return blocks.stream()
                .map(block -> block.withReachable(reachable.contains(block.id())))
                .toList();
    }
}
