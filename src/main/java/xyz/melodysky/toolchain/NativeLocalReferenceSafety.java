package xyz.melodysky.toolchain;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrExceptionEdge;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrType;

/**
 * Detects methods that need ownership-aware JNI local-reference planning.
 *
 * <p>JNI frees local references when the registered native method returns, not
 * when a generated helper returns. A reference-producing helper in a cycle
 * therefore requires a validated ownership/last-use plan; the implementation
 * planner fails the complete method closed when that plan cannot prove bounded
 * release on every reachable normal and exceptional path.</p>
 */
public final class NativeLocalReferenceSafety {
    public static final String UNBOUNDED_REASON_CODE =
            "UNBOUNDED_JNI_LOCAL_REFERENCE_LIFETIME";

    public boolean hasUnboundedLocalReferenceRisk(IrMethod method) {
        if (method.blocks().isEmpty()) {
            return false;
        }
        Map<String, List<String>> successors = successors(method);
        Set<String> reachable = reachable(
                method.blocks().get(0).name(),
                successors);
        return method.blocks().stream()
                .filter(block -> reachable.contains(block.name()))
                .filter(this::createsOwnedLocalReference)
                .anyMatch(block -> participatesInCycle(
                        block.name(),
                        successors));
    }

    /**
     * Returns whether a reachable instruction creates a JNI local reference
     * owned by the current registered-native activation.
     */
    public boolean createsOwnedLocalReference(IrMethod method) {
        if (method.blocks().isEmpty()) {
            return false;
        }
        Set<String> reachable = reachable(
                method.blocks().get(0).name(),
                successors(method));
        return method.blocks().stream()
                .filter(block -> reachable.contains(block.name()))
                .anyMatch(this::createsOwnedLocalReference);
    }

    private boolean createsOwnedLocalReference(IrBlock block) {
        return block.instructions().stream()
                .anyMatch(this::createsOwnedLocalReference);
    }

    private boolean createsOwnedLocalReference(IrInstruction instruction) {
        if (instruction.exceptionSites().stream()
                .flatMap(site -> site.exceptionValue().stream())
                .anyMatch(value -> value.type() == IrType.REFERENCE)) {
            return true;
        }
        if (instruction.result()
                .map(result -> result.type() != IrType.REFERENCE)
                .orElse(true)) {
            return false;
        }
        /*
         * null owns no JNI handle. CHECKCAST returns its borrowed input after
         * an IsInstanceOf check; it does not create a new local reference.
         * All other reference-producing instructions are conservatively
         * treated as owned until the backend records origin and last use.
         */
        return instruction.opcode() != IrOpcode.CONST_NULL
                && instruction.opcode() != IrOpcode.CHECKCAST;
    }

    private Map<String, List<String>> successors(IrMethod method) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        method.blocks().stream().map(IrBlock::name).forEach(names::add);
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<>();
        for (IrBlock block : method.blocks()) {
            LinkedHashSet<String> targets = new LinkedHashSet<>();
            IrTerminator terminator = block.terminator();
            terminator.target().filter(names::contains).ifPresent(targets::add);
            terminator.trueTarget().filter(names::contains).ifPresent(targets::add);
            terminator.falseTarget().filter(names::contains).ifPresent(targets::add);
            terminator.defaultTarget().filter(names::contains).ifPresent(targets::add);
            terminator.switchCases().stream()
                    .map(switchCase -> switchCase.target())
                    .filter(names::contains)
                    .forEach(targets::add);
            block.exceptionEdges().stream()
                    .map(IrExceptionEdge::target)
                    .filter(names::contains)
                    .forEach(targets::add);
            block.instructions().stream()
                    .flatMap(instruction -> instruction.exceptionSites().stream())
                    .flatMap(site -> site.handlers().stream())
                    .map(IrExceptionEdge::target)
                    .filter(names::contains)
                    .forEach(targets::add);
            result.put(block.name(), List.copyOf(targets));
        }
        return Map.copyOf(result);
    }

    private Set<String> reachable(
            String entry,
            Map<String, List<String>> successors) {
        LinkedHashSet<String> reached = new LinkedHashSet<>();
        ArrayDeque<String> work = new ArrayDeque<>();
        work.add(entry);
        while (!work.isEmpty()) {
            String block = work.removeFirst();
            if (reached.add(block)) {
                successors.getOrDefault(block, List.of())
                        .forEach(work::addLast);
            }
        }
        return Set.copyOf(reached);
    }

    private boolean participatesInCycle(
            String start,
            Map<String, List<String>> successors) {
        ArrayDeque<String> work = new ArrayDeque<>(
                successors.getOrDefault(start, List.of()));
        LinkedHashSet<String> visited = new LinkedHashSet<>();
        while (!work.isEmpty()) {
            String current = work.removeFirst();
            if (current.equals(start)) {
                return true;
            }
            if (visited.add(current)) {
                successors.getOrDefault(current, List.of())
                        .forEach(work::addLast);
            }
        }
        return false;
    }
}
