package xyz.melodysky.ir.pass.protection;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrTerminatorKind;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;

/** Selects bounded single-entry regions without rewriting the input method. */
public final class ControlFlowFlatteningRegionPlanner {
    public static final String APPLIED_REASON = "CONTROL_FLOW_FLATTENING";
    public static final String STUB_BACKED_REASON = "PROTECTION_STUB_BACKED_METHOD";
    public static final String OWNED_LOCAL_REFERENCE_REASON =
            "CONTROL_FLOW_FLATTENING_OWNED_LOCAL_REFERENCE";
    public static final String CROSS_BLOCK_SSA_REASON =
            "CONTROL_FLOW_FLATTENING_CROSS_BLOCK_SSA_VALUE";
    public static final String UNSUPPORTED_SHAPE_REASON =
            "CONTROL_FLOW_FLATTENING_UNSUPPORTED_SHAPE";

    public ControlFlowFlatteningPlan plan(IrMethod method, long seed) {
        Objects.requireNonNull(method, "method");
        if (isStubBackedMethod(method)) {
            return ControlFlowFlatteningPlan.skipped(method.methodKey(), STUB_BACKED_REASON);
        }

        MethodFacts facts = MethodFacts.analyze(method);
        List<List<String>> selected = selectRegions(method, facts, EligibilityMode.STRICT);
        if (!selected.isEmpty()) {
            return ControlFlowFlatteningPlan.selected(
                    method.methodKey(),
                    materialize(method, selected, seed));
        }

        List<List<String>> relaxed = selectRegions(method, facts, EligibilityMode.REASON_ONLY);
        if (!relaxed.isEmpty()) {
            boolean owned = relaxed.stream()
                    .flatMap(List::stream)
                    .anyMatch(facts.ownedProducerBlocks()::contains);
            if (owned) {
                return ControlFlowFlatteningPlan.skipped(
                        method.methodKey(),
                        OWNED_LOCAL_REFERENCE_REASON);
            }
            boolean crossBlockSsa = relaxed.stream()
                    .flatMap(List::stream)
                    .anyMatch(facts.crossBlockDefinitionBlocks()::contains);
            if (crossBlockSsa) {
                return ControlFlowFlatteningPlan.skipped(
                        method.methodKey(),
                        CROSS_BLOCK_SSA_REASON);
            }
        }
        return ControlFlowFlatteningPlan.skipped(
                method.methodKey(),
                UNSUPPORTED_SHAPE_REASON);
    }

    private List<ControlFlowFlatteningRegion> materialize(
            IrMethod method,
            List<List<String>> selected,
            long seed) {
        ProtectionRandom random = new ProtectionRandom(seed);
        ArrayList<ControlFlowFlatteningRegion> regions = new ArrayList<>();
        for (List<String> members : selected) {
            String entry = members.get(0);
            String membership = members.stream().sorted().collect(
                    java.util.stream.Collectors.joining("\u0000"));
            String regionId = random.token(
                    "CONTROL_FLOW_FLATTENING:REGION_ID",
                    method.methodKey() + "\u0000" + entry + "\u0000" + membership,
                    20);
            ArrayList<String> ranked = new ArrayList<>(members);
            ranked.sort(Comparator
                    .comparing((String block) -> random.token(
                            "CONTROL_FLOW_FLATTENING:REGION_STATE_RANK",
                            method.methodKey() + "\u0000" + regionId + "\u0000" + block,
                            64))
                    .thenComparing(block -> block));
            HashMap<String, Integer> stateByBlock = new HashMap<>();
            for (int state = 0; state < ranked.size(); state++) {
                stateByBlock.put(ranked.get(state), state);
            }
            LinkedHashMap<String, Integer> stableStates = new LinkedHashMap<>();
            members.forEach(block -> stableStates.put(block, stateByBlock.get(block)));
            regions.add(new ControlFlowFlatteningRegion(
                    regionId,
                    entry,
                    members,
                    stableStates));
        }
        return List.copyOf(regions);
    }

    private List<List<String>> selectRegions(
            IrMethod method,
            MethodFacts facts,
            EligibilityMode mode) {
        LinkedHashSet<String> available = method.blocks().stream()
                .filter(block -> eligible(block, facts, mode))
                .map(IrBlock::name)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        String methodEntry = method.blocks().get(0).name();
        ArrayList<List<String>> regions = new ArrayList<>();
        for (IrBlock header : method.blocks()) {
            if (regions.size() == ControlFlowFlatteningPlan.MAX_REGIONS) {
                break;
            }
            if (!available.contains(header.name())) {
                continue;
            }
            LinkedHashSet<String> candidate = reachable(
                    header.name(),
                    available,
                    facts.successors());
            if (!header.name().equals(methodEntry)) {
                candidate.remove(methodEntry);
            }
            candidate = boundedCandidate(method, header.name(), candidate);
            pruneAdditionalEntries(
                    header.name(),
                    candidate,
                    facts.predecessors(),
                    facts.successors());
            if (candidate.size() < 2) {
                continue;
            }
            ArrayList<String> members = new ArrayList<>();
            members.add(header.name());
            method.blocks().stream()
                    .map(IrBlock::name)
                    .filter(candidate::contains)
                    .filter(name -> !name.equals(header.name()))
                    .forEach(members::add);
            regions.add(List.copyOf(members));
            available.removeAll(candidate);
        }
        return List.copyOf(regions);
    }

    private LinkedHashSet<String> boundedCandidate(
            IrMethod method,
            String header,
            Set<String> candidate) {
        LinkedHashSet<String> bounded = new LinkedHashSet<>();
        if (candidate.contains(header)) {
            bounded.add(header);
        }
        method.blocks().stream()
                .map(IrBlock::name)
                .filter(candidate::contains)
                .filter(block -> !block.equals(header))
                .limit(ControlFlowFlatteningRegion.MAX_MEMBER_BLOCKS - 1L)
                .forEach(bounded::add);
        return bounded;
    }

    private void pruneAdditionalEntries(
            String header,
            LinkedHashSet<String> candidate,
            Map<String, List<String>> predecessors,
            Map<String, List<String>> successors) {
        boolean changed;
        do {
            changed = false;
            ArrayList<String> remove = new ArrayList<>();
            for (String block : candidate) {
                if (block.equals(header)) {
                    continue;
                }
                if (predecessors.getOrDefault(block, List.of()).stream()
                        .anyMatch(predecessor -> !candidate.contains(predecessor))) {
                    remove.add(block);
                }
            }
            if (!remove.isEmpty()) {
                candidate.removeAll(remove);
                changed = true;
            }
            LinkedHashSet<String> stillReachable = reachable(header, candidate, successors);
            if (!stillReachable.equals(candidate)) {
                candidate.retainAll(stillReachable);
                changed = true;
            }
        } while (changed);
    }

    private LinkedHashSet<String> reachable(
            String entry,
            Set<String> allowed,
            Map<String, List<String>> successors) {
        LinkedHashSet<String> reached = new LinkedHashSet<>();
        if (!allowed.contains(entry)) {
            return reached;
        }
        ArrayDeque<String> work = new ArrayDeque<>();
        work.add(entry);
        while (!work.isEmpty()) {
            String block = work.removeFirst();
            if (!allowed.contains(block) || !reached.add(block)) {
                continue;
            }
            successors.getOrDefault(block, List.of()).forEach(work::addLast);
        }
        return reached;
    }

    private boolean eligible(
            IrBlock block,
            MethodFacts facts,
            EligibilityMode mode) {
        boolean structurallyEligible = mode == EligibilityMode.STRICT
                ? structurallyEligible(block)
                : reasonStructurallyEligible(block);
        if (!structurallyEligible) {
            return false;
        }
        if (mode == EligibilityMode.STRICT
                && (facts.ownedProducerBlocks().contains(block.name())
                        || facts.crossBlockDefinitionBlocks().contains(block.name()))) {
            return false;
        }
        return true;
    }

    private boolean structurallyEligible(IrBlock block) {
        if (!hasBaseStructuralShape(block)
                || block.instructions().stream().anyMatch(instruction ->
                        !instruction.exceptionSites().isEmpty()
                                || isMonitorJmmOrClassInit(instruction.opcode()))) {
            return false;
        }
        return block.terminator().kind() != IrTerminatorKind.BRANCH
                || block.terminator().condition()
                        .map(IrValue::type)
                        .filter(type -> type != IrType.I1)
                        .isEmpty();
    }

    private boolean reasonStructurallyEligible(IrBlock block) {
        if (!hasBaseStructuralShape(block)
                || !hasSafeClassInitializationOrdering(block)
                || block.instructions().stream().anyMatch(instruction ->
                        instruction.exceptionSites().stream().anyMatch(site -> !site.handlers().isEmpty())
                                || isReasonSensitiveOpcode(instruction.opcode()))) {
            return false;
        }
        return block.terminator().kind() != IrTerminatorKind.BRANCH
                || block.terminator().condition()
                        .map(IrValue::type)
                        .filter(type -> type != IrType.I1)
                        .isEmpty();
    }

    private boolean hasBaseStructuralShape(IrBlock block) {
        IrTerminator terminator = block.terminator();
        return block.parameters().isEmpty()
                && !block.isExceptionHandler()
                && block.exceptionCatchTypes().isEmpty()
                && block.exceptionEdges().isEmpty()
                && (terminator.kind() == IrTerminatorKind.GOTO
                        || terminator.kind() == IrTerminatorKind.BRANCH
                        || terminator.kind() == IrTerminatorKind.RETURN)
                && terminator.targetArguments().isEmpty()
                && terminator.trueTargetArguments().isEmpty()
                && terminator.falseTargetArguments().isEmpty()
                && terminator.defaultTargetArguments().isEmpty()
                && terminator.switchCases().stream().allMatch(switchCase ->
                        switchCase.arguments().isEmpty());
    }

    private boolean isReasonSensitiveOpcode(IrOpcode opcode) {
        return switch (opcode) {
            case CLASS_INIT_BEGIN,
                    CLASS_INIT_END,
                    CLASS_INIT_FAILED,
                    MONITOR_ENTER,
                    MONITOR_EXIT,
                    MONITOR_EXIT_ON_EXCEPTION,
                    VOLATILE_READ_BARRIER,
                    VOLATILE_WRITE_BARRIER,
                    FINAL_FIELD_PUBLICATION,
                    MONITOR_HAPPENS_BEFORE,
                    THREAD_START_HAPPENS_BEFORE,
                    THREAD_JOIN_HAPPENS_BEFORE -> true;
            default -> false;
        };
    }

    private boolean hasSafeClassInitializationOrdering(IrBlock block) {
        List<IrInstruction> instructions = block.instructions();
        for (int index = 0; index < instructions.size(); index++) {
            IrInstruction instruction = instructions.get(index);
            if (instruction.opcode() == IrOpcode.CLASS_INIT_BEGIN
                    || instruction.opcode() == IrOpcode.CLASS_INIT_END
                    || instruction.opcode() == IrOpcode.CLASS_INIT_FAILED) {
                return false;
            }
            if (instruction.opcode() == IrOpcode.CLASS_INIT_GUARD) {
                if (index == 0
                        || index + 1 >= instructions.size()
                        || instructions.get(index - 1).opcode() != IrOpcode.CLASS_OBJECT
                        || instructions.get(index + 1).opcode() != IrOpcode.CLASS_INIT_HAPPENS_BEFORE
                        || instruction.operands().size() != 1
                        || instructions.get(index - 1).result().isEmpty()
                        || !instructions.get(index - 1).result().orElseThrow()
                                .equals(instruction.operands().get(0))
                        || !instructions.get(index + 1).operands().equals(instruction.operands())) {
                    return false;
                }
            }
            if (instruction.opcode() == IrOpcode.CLASS_INIT_HAPPENS_BEFORE
                    && (index == 0
                            || instructions.get(index - 1).opcode() != IrOpcode.CLASS_INIT_GUARD)) {
                return false;
            }
            if (instruction.opcode() == IrOpcode.CLASS_INIT_ACTIVE_USE) {
                if (index == 0
                        || !isFusedActiveUse(instructions.get(index - 1), instruction)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean isMonitorJmmOrClassInit(IrOpcode opcode) {
        return switch (opcode) {
            case CLASS_OBJECT,
                    CLASS_INIT_GUARD,
                    CLASS_INIT_BEGIN,
                    CLASS_INIT_END,
                    CLASS_INIT_FAILED,
                    CLASS_INIT_HAPPENS_BEFORE,
                    CLASS_INIT_ACTIVE_USE,
                    MONITOR_ENTER,
                    MONITOR_EXIT,
                    MONITOR_EXIT_ON_EXCEPTION,
                    VOLATILE_READ_BARRIER,
                    VOLATILE_WRITE_BARRIER,
                    FINAL_FIELD_PUBLICATION,
                    MONITOR_HAPPENS_BEFORE,
                    THREAD_START_HAPPENS_BEFORE,
                    THREAD_JOIN_HAPPENS_BEFORE -> true;
            default -> false;
        };
    }

    private boolean isFusedActiveUse(
            IrInstruction activeUse,
            IrInstruction marker) {
        String classSymbol = marker.symbol().orElse("");
        if (!classSymbol.startsWith("class:L") || !classSymbol.endsWith(";")) {
            return false;
        }
        String owner = classSymbol.substring("class:L".length(), classSymbol.length() - 1);
        if (activeUse.opcode() != IrOpcode.GET_STATIC
                && activeUse.opcode() != IrOpcode.PUT_STATIC
                && activeUse.opcode() != IrOpcode.CALL_STATIC) {
            return false;
        }
        return activeUse.symbol()
                .filter(symbol -> symbol.startsWith(owner + "#"))
                .isPresent();
    }

    private boolean isStubBackedMethod(IrMethod method) {
        return method.name().equals("<init>") || method.name().equals("<clinit>");
    }

    private enum EligibilityMode {
        STRICT,
        REASON_ONLY
    }

    private record MethodFacts(
            Map<String, List<String>> successors,
            Map<String, List<String>> predecessors,
            Set<String> ownedProducerBlocks,
            Set<String> crossBlockDefinitionBlocks) {
        private static MethodFacts analyze(IrMethod method) {
            LinkedHashMap<String, List<String>> successors = normalSuccessors(method);
            LinkedHashMap<String, ArrayList<String>> mutablePredecessors = new LinkedHashMap<>();
            method.blocks().forEach(block ->
                    mutablePredecessors.put(block.name(), new ArrayList<>()));
            successors.forEach((source, targets) -> targets.forEach(target -> {
                List<String> predecessors = mutablePredecessors.get(target);
                if (predecessors != null && !predecessors.contains(source)) {
                    predecessors.add(source);
                }
            }));
            for (IrBlock block : method.blocks()) {
                block.exceptionEdges().forEach(edge -> addPredecessor(
                        mutablePredecessors,
                        edge.target(),
                        block.name()));
                block.instructions().stream()
                        .flatMap(instruction -> instruction.exceptionSites().stream())
                        .flatMap(site -> site.handlers().stream())
                        .forEach(edge -> addPredecessor(
                                mutablePredecessors,
                                edge.target(),
                                block.name()));
            }
            LinkedHashMap<String, List<String>> predecessors = new LinkedHashMap<>();
            mutablePredecessors.forEach((block, sources) ->
                    predecessors.put(block, List.copyOf(sources)));

            HashMap<IrValue, String> definitions = new HashMap<>();
            for (IrBlock block : method.blocks()) {
                block.instructions().forEach(instruction -> {
                    instruction.result().ifPresent(result -> definitions.put(result, block.name()));
                    instruction.exceptionSites().forEach(site -> site.exceptionValue()
                            .ifPresent(result -> definitions.put(result, block.name())));
                });
            }
            LinkedHashSet<String> crossBlockDefinitions = new LinkedHashSet<>();
            for (IrBlock block : method.blocks()) {
                uses(block).stream()
                        .map(definitions::get)
                        .filter(Objects::nonNull)
                        .filter(definitionBlock -> !definitionBlock.equals(block.name()))
                        .forEach(crossBlockDefinitions::add);
            }

            LinkedHashSet<String> owned = method.blocks().stream()
                    .filter(block -> block.instructions().stream()
                            .anyMatch(MethodFacts::createsOwnedLocalReference))
                    .map(IrBlock::name)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            return new MethodFacts(
                    Collections.unmodifiableMap(successors),
                    Collections.unmodifiableMap(predecessors),
                    Set.copyOf(owned),
                    Set.copyOf(crossBlockDefinitions));
        }

        private static void addPredecessor(
                Map<String, ArrayList<String>> predecessors,
                String target,
                String source) {
            ArrayList<String> sources = predecessors.get(target);
            if (sources != null && !sources.contains(source)) {
                sources.add(source);
            }
        }

        private static LinkedHashMap<String, List<String>> normalSuccessors(IrMethod method) {
            LinkedHashSet<String> names = method.blocks().stream()
                    .map(IrBlock::name)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
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
                result.put(block.name(), List.copyOf(targets));
            }
            return result;
        }

        private static List<IrValue> uses(IrBlock block) {
            ArrayList<IrValue> uses = new ArrayList<>();
            block.instructions().forEach(instruction -> {
                uses.addAll(instruction.operands());
                instruction.exceptionSites().forEach(site ->
                        site.handlers().forEach(edge -> uses.addAll(edge.arguments())));
            });
            IrTerminator terminator = block.terminator();
            terminator.value().ifPresent(uses::add);
            terminator.condition().ifPresent(uses::add);
            terminator.switchValue().ifPresent(uses::add);
            uses.addAll(terminator.targetArguments());
            uses.addAll(terminator.trueTargetArguments());
            uses.addAll(terminator.falseTargetArguments());
            uses.addAll(terminator.defaultTargetArguments());
            terminator.switchCases().forEach(switchCase -> uses.addAll(switchCase.arguments()));
            block.exceptionEdges().forEach(edge -> uses.addAll(edge.arguments()));
            return List.copyOf(uses);
        }

        private static boolean createsOwnedLocalReference(IrInstruction instruction) {
            boolean exceptionReference = instruction.exceptionSites().stream()
                    .flatMap(site -> site.exceptionValue().stream())
                    .anyMatch(value -> value.type() == IrType.REFERENCE);
            if (exceptionReference) {
                return true;
            }
            boolean referenceResult = instruction.result()
                    .map(result -> result.type() == IrType.REFERENCE)
                    .orElse(false);
            return referenceResult
                    && instruction.opcode() != IrOpcode.CONST_NULL
                    && instruction.opcode() != IrOpcode.CHECKCAST;
        }
    }
}
