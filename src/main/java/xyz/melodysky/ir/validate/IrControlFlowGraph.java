package xyz.melodysky.ir.validate;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrExceptionEdge;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrTerminator;

final class IrControlFlowGraph {
    private final Set<String> reachableBlocks;
    private final Map<String, Set<String>> dominators;

    private IrControlFlowGraph(
            Set<String> reachableBlocks,
            Map<String, Set<String>> dominators) {
        this.reachableBlocks = Set.copyOf(reachableBlocks);
        this.dominators = Map.copyOf(dominators);
    }

    static IrControlFlowGraph analyze(
            IrMethod method,
            Map<String, IrBlock> blocksByName) {
        Map<String, List<String>> successors = successors(method, blocksByName);
        Set<String> reachable = reachable(method.blocks().get(0).name(), successors);
        return new IrControlFlowGraph(
                reachable,
                dominators(method, reachable, successors));
    }

    boolean isReachable(String block) {
        return reachableBlocks.contains(block);
    }

    boolean dominates(String definitionBlock, String useBlock) {
        return dominators.getOrDefault(useBlock, Set.of()).contains(definitionBlock);
    }

    private static Map<String, List<String>> successors(
            IrMethod method,
            Map<String, IrBlock> blocksByName) {
        LinkedHashMap<String, List<String>> successors = new LinkedHashMap<>();
        for (IrBlock block : method.blocks()) {
            LinkedHashSet<String> targets = new LinkedHashSet<>();
            IrTerminator terminator = block.terminator();
            terminator.target().filter(blocksByName::containsKey).ifPresent(targets::add);
            terminator.trueTarget().filter(blocksByName::containsKey).ifPresent(targets::add);
            terminator.falseTarget().filter(blocksByName::containsKey).ifPresent(targets::add);
            terminator.defaultTarget().filter(blocksByName::containsKey).ifPresent(targets::add);
            terminator.switchCases().stream()
                    .map(switchCase -> switchCase.target())
                    .filter(blocksByName::containsKey)
                    .forEach(targets::add);
            block.exceptionEdges().stream()
                    .map(IrExceptionEdge::target)
                    .filter(blocksByName::containsKey)
                    .forEach(targets::add);
            block.instructions().stream()
                    .flatMap(instruction -> instruction.exceptionSites().stream())
                    .flatMap(site -> site.handlers().stream())
                    .map(IrExceptionEdge::target)
                    .filter(blocksByName::containsKey)
                    .forEach(targets::add);
            successors.put(block.name(), List.copyOf(targets));
        }
        return successors;
    }

    private static Set<String> reachable(
            String entry,
            Map<String, List<String>> successors) {
        LinkedHashSet<String> reachable = new LinkedHashSet<>();
        ArrayDeque<String> worklist = new ArrayDeque<>();
        worklist.add(entry);
        while (!worklist.isEmpty()) {
            String block = worklist.removeFirst();
            if (!reachable.add(block)) {
                continue;
            }
            successors.getOrDefault(block, List.of()).forEach(worklist::addLast);
        }
        return Set.copyOf(reachable);
    }

    private static Map<String, Set<String>> dominators(
            IrMethod method,
            Set<String> reachable,
            Map<String, List<String>> successors) {
        String entry = method.blocks().get(0).name();
        LinkedHashMap<String, Set<String>> predecessors = new LinkedHashMap<>();
        for (IrBlock block : method.blocks()) {
            if (reachable.contains(block.name())) {
                predecessors.put(block.name(), new LinkedHashSet<>());
            }
        }
        for (Map.Entry<String, List<String>> edge : successors.entrySet()) {
            if (!reachable.contains(edge.getKey())) {
                continue;
            }
            for (String target : edge.getValue()) {
                if (reachable.contains(target)) {
                    predecessors.get(target).add(edge.getKey());
                }
            }
        }

        LinkedHashSet<String> allReachable = new LinkedHashSet<>();
        method.blocks().stream()
                .map(IrBlock::name)
                .filter(reachable::contains)
                .forEach(allReachable::add);
        LinkedHashMap<String, Set<String>> dominators = new LinkedHashMap<>();
        for (String block : allReachable) {
            dominators.put(
                    block,
                    block.equals(entry)
                            ? Set.of(entry)
                            : new LinkedHashSet<>(allReachable));
        }

        boolean changed;
        do {
            changed = false;
            for (String block : allReachable) {
                if (block.equals(entry)) {
                    continue;
                }
                LinkedHashSet<String> updated = new LinkedHashSet<>(allReachable);
                for (String predecessor : predecessors.get(block)) {
                    updated.retainAll(dominators.get(predecessor));
                }
                updated.add(block);
                if (!updated.equals(dominators.get(block))) {
                    dominators.put(block, updated);
                    changed = true;
                }
            }
        } while (changed);
        return Map.copyOf(dominators);
    }
}
