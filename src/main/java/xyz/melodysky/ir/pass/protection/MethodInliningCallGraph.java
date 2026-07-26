package xyz.melodysky.ir.pass.protection;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrProgram;

final class MethodInliningCallGraph {
    private final Map<String, Set<String>> outgoing;

    MethodInliningCallGraph(IrProgram program) {
        HashSet<String> programMethods = new HashSet<>();
        for (var irClass : program.classes()) {
            irClass.methods().stream().map(IrMethod::methodKey).forEach(programMethods::add);
        }
        HashMap<String, Set<String>> edges = new HashMap<>();
        for (var irClass : program.classes()) {
            for (IrMethod method : irClass.methods()) {
                HashSet<String> targets = new HashSet<>();
                method.blocks().stream()
                        .flatMap(block -> block.instructions().stream())
                        .filter(instruction -> instruction.opcode() == IrOpcode.CALL_STATIC
                                || instruction.opcode() == IrOpcode.CALL_SPECIAL)
                        .flatMap(instruction -> instruction.symbol().stream())
                        .filter(programMethods::contains)
                        .forEach(targets::add);
                edges.put(method.methodKey(), Set.copyOf(targets));
            }
        }
        outgoing = Map.copyOf(edges);
    }

    boolean isRecursiveEdge(String callerMethodKey, String calleeMethodKey) {
        if (callerMethodKey.equals(calleeMethodKey)) {
            return true;
        }
        ArrayDeque<String> worklist = new ArrayDeque<>();
        HashSet<String> visited = new HashSet<>();
        worklist.add(calleeMethodKey);
        while (!worklist.isEmpty()) {
            String current = worklist.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            if (current.equals(callerMethodKey)) {
                return true;
            }
            outgoing.getOrDefault(current, Set.of()).forEach(worklist::addLast);
        }
        return false;
    }
}
