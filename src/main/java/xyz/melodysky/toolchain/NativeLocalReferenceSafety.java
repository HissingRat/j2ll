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
 * Conservative boundary for JNI local references created inside native loops.
 *
 * <p>JNI frees local references when the registered native method returns, not
 * when a generated helper returns. Until the LLVM backend has an
 * ownership-aware last-use {@code DeleteLocalRef} plan, a reference-producing
 * helper in a cycle could grow the caller's local-reference table without a
 * bound. Such a method must stay in Java instead of being lowered with a
 * latent resource failure.</p>
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

    /**
     * Returns direct LLVM-call candidates reached by this method.
     *
     * <p>The caller decides which candidates are actually part of the final
     * direct-call closure. Calls lowered through a JVM/JNI dispatch bridge
     * must not be included in that closure.</p>
     */
    public Set<String> reachableDirectCallTargets(IrMethod method) {
        if (method.blocks().isEmpty()) {
            return Set.of();
        }
        Set<String> reachable = reachable(
                method.blocks().get(0).name(),
                successors(method));
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        method.blocks().stream()
                .filter(block -> reachable.contains(block.name()))
                .flatMap(block -> block.instructions().stream())
                .filter(this::isDirectCallCandidate)
                .flatMap(instruction -> instruction.symbol().stream())
                .forEach(targets::add);
        return Set.copyOf(targets);
    }

    /**
     * Returns direct LLVM-call candidates whose call instruction is in a
     * reachable control-flow cycle.
     */
    public Set<String> directCallTargetsInCycles(IrMethod method) {
        if (method.blocks().isEmpty()) {
            return Set.of();
        }
        Map<String, List<String>> successors = successors(method);
        Set<String> reachable = reachable(
                method.blocks().get(0).name(),
                successors);
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        method.blocks().stream()
                .filter(block -> reachable.contains(block.name()))
                .filter(block -> participatesInCycle(
                        block.name(),
                        successors))
                .flatMap(block -> block.instructions().stream())
                .filter(this::isDirectCallCandidate)
                .flatMap(instruction -> instruction.symbol().stream())
                .forEach(targets::add);
        return Set.copyOf(targets);
    }

    private boolean createsOwnedLocalReference(IrBlock block) {
        return block.instructions().stream()
                .anyMatch(this::createsOwnedLocalReference);
    }

    private boolean createsOwnedLocalReference(IrInstruction instruction) {
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

    private boolean isDirectCallCandidate(IrInstruction instruction) {
        if (instruction.opcode() == IrOpcode.CALL_STATIC) {
            return instruction.symbol().isPresent();
        }
        return instruction.opcode() == IrOpcode.CALL_SPECIAL
                && instruction.symbol()
                        .map(symbol -> !symbol.contains("#<init>!"))
                        .orElse(false);
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
