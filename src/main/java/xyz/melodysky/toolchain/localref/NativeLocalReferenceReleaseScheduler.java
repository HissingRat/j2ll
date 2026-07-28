package xyz.melodysky.toolchain.localref;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrTerminatorKind;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;

/**
 * Schedules normal, exceptional and edge-specific local-reference releases.
 */
final class NativeLocalReferenceReleaseScheduler {
    private final NativeLocalReferenceOwnershipClassifier ownershipClassifier;

    NativeLocalReferenceReleaseScheduler(
            NativeLocalReferenceOwnershipClassifier ownershipClassifier) {
        this.ownershipClassifier = ownershipClassifier;
    }

    NativeLocalReferencePlan schedule(
            IrMethod method,
            Map<String, NativeLocalReferenceOwnership> ownership,
            NativeLocalReferenceCfgFacts cfg) {
        LinkedHashMap<
                        NativeLocalReferenceInstructionSite,
                        MutableReleaseSchedule>
                instructionReleases = new LinkedHashMap<>();
        LinkedHashMap<String, LinkedHashSet<IrValue>>
                terminatorReleases = new LinkedHashMap<>();
        LinkedHashMap<
                        NativeLocalReferenceNormalEdge,
                        LinkedHashSet<IrValue>>
                edgeReleases = new LinkedHashMap<>();

        for (IrBlock block : method.blocks()) {
            planBlock(
                    block,
                    ownership,
                    cfg,
                    instructionReleases,
                    terminatorReleases,
                    edgeReleases);
        }
        return new NativeLocalReferencePlan(
                method.methodKey(),
                ownership,
                freezeInstructionReleases(instructionReleases),
                freezeReleases(terminatorReleases),
                freezeEdgeReleases(edgeReleases));
    }

    private void planBlock(
            IrBlock block,
            Map<String, NativeLocalReferenceOwnership> ownership,
            NativeLocalReferenceCfgFacts cfg,
            Map<
                            NativeLocalReferenceInstructionSite,
                            MutableReleaseSchedule>
                    instructionReleases,
            Map<String, LinkedHashSet<IrValue>> terminatorReleases,
            Map<
                            NativeLocalReferenceNormalEdge,
                            LinkedHashSet<IrValue>>
                    edgeReleases) {
        LinkedHashSet<IrValue> live =
                new LinkedHashSet<>(cfg.liveOut(block.name()));
        NativeLocalReferenceCfgFacts.referenceTerminatorUses(
                        block.terminator())
                .forEach(live::add);
        cfg.explicitThrowNeeded(block).forEach(live::add);
        LinkedHashSet<IrValue> liveAtTerminator =
                new LinkedHashSet<>(live);

        List<IrInstruction> instructions = block.instructions();
        for (int index = instructions.size() - 1; index >= 0; index--) {
            IrInstruction instruction = instructions.get(index);
            NativeLocalReferenceInstructionSite site =
                    new NativeLocalReferenceInstructionSite(
                            block.name(),
                            index);
            LinkedHashSet<IrValue> normalAfter =
                    new LinkedHashSet<>(live);
            LinkedHashSet<IrValue> exceptionalNeeded =
                    cfg.exceptionalNeeded(instruction);

            LinkedHashSet<IrValue> normalCandidates =
                    new LinkedHashSet<>();
            NativeLocalReferenceCfgFacts.referenceOperands(instruction)
                    .stream()
                    .filter(value -> !normalAfter.contains(value))
                    .forEach(normalCandidates::add);
            exceptionalNeeded.stream()
                    .filter(value -> !normalAfter.contains(value))
                    .forEach(normalCandidates::add);
            instruction.result()
                    .filter(value -> value.type() == IrType.REFERENCE)
                    .filter(value -> !normalAfter.contains(value))
                    .ifPresent(normalCandidates::add);

            IrValue aliasSource = ownershipClassifier
                    .aliasSource(instruction)
                    .orElse(null);
            if (aliasSource != null) {
                normalCandidates.remove(aliasSource);
            }
            normalCandidates.stream()
                    .filter(value ->
                            ownershipClassifier.shouldEmitRelease(
                                    value,
                                    ownership))
                    .forEach(value -> releasesAt(
                            instructionReleases,
                            site).normal.add(value));

            instruction.result()
                    .filter(value -> value.type() == IrType.REFERENCE)
                    .ifPresent(live::remove);
            LinkedHashSet<IrValue> exceptionDefinitions =
                    instruction.exceptionSites().stream()
                    .flatMap(exceptionSite ->
                            exceptionSite.exceptionValue().stream())
                    .filter(value -> value.type() == IrType.REFERENCE)
                    .collect(java.util.stream.Collectors.toCollection(
                            LinkedHashSet::new));
            exceptionDefinitions.forEach(live::remove);
            NativeLocalReferenceCfgFacts.referenceOperands(instruction)
                    .forEach(live::add);
            exceptionalNeeded.stream()
                    .filter(value -> !exceptionDefinitions.contains(value))
                    .forEach(live::add);
        }

        planExceptionalReleases(
                block,
                ownership,
                cfg,
                instructionReleases);

        if (isActivationExit(block)) {
            return;
        }

        block.parameters().stream()
                .filter(value -> value.type() == IrType.REFERENCE)
                .filter(value -> !liveAtTerminator.contains(value))
                .filter(value -> noInstructionRelease(
                        block.name(),
                        value,
                        instructionReleases))
                .filter(value ->
                        ownershipClassifier.shouldEmitRelease(
                                value,
                                ownership))
                .forEach(value -> terminatorReleases
                        .computeIfAbsent(
                                block.name(),
                                ignored -> new LinkedHashSet<>())
                        .add(value));

        List<NativeLocalReferenceCfgFacts.NormalEdgeFacts> edges =
                cfg.normalEdges(block.name());
        for (IrValue value : liveAtTerminator) {
            if (!ownershipClassifier.shouldEmitRelease(
                            value,
                            ownership)
                    || edges.isEmpty()) {
                continue;
            }
            long neededEdges = edges.stream()
                    .filter(edge -> cfg.edgeNeeds(value, edge))
                    .count();
            if (neededEdges == 0 || neededEdges == edges.size()) {
                continue;
            }
            for (NativeLocalReferenceCfgFacts.NormalEdgeFacts edge :
                    edges) {
                if (!cfg.edgeNeeds(value, edge)) {
                    edgeReleases
                            .computeIfAbsent(
                                    edge.edge(),
                                    ignored -> new LinkedHashSet<>())
                            .add(value);
                }
            }
        }
    }

    private void planExceptionalReleases(
            IrBlock block,
            Map<String, NativeLocalReferenceOwnership> ownership,
            NativeLocalReferenceCfgFacts cfg,
            Map<
                            NativeLocalReferenceInstructionSite,
                            MutableReleaseSchedule>
                    instructionReleases) {
        LinkedHashSet<IrValue> held = new LinkedHashSet<>();
        block.parameters().stream()
                .filter(value -> value.type() == IrType.REFERENCE)
                .filter(value -> ownershipClassifier.shouldEmitRelease(
                        value,
                        ownership))
                .forEach(held::add);
        cfg.liveIn()
                .getOrDefault(block.name(), Set.of())
                .stream()
                .filter(value -> value.type() == IrType.REFERENCE)
                .filter(value -> ownershipClassifier.shouldEmitRelease(
                        value,
                        ownership))
                .forEach(held::add);

        List<IrInstruction> instructions = block.instructions();
        for (int index = 0; index < instructions.size(); index++) {
            IrInstruction instruction = instructions.get(index);
            NativeLocalReferenceInstructionSite site =
                    new NativeLocalReferenceInstructionSite(
                            block.name(),
                            index);
            LinkedHashSet<IrValue> exceptionDefinitions =
                    exceptionDefinitions(instruction);
            if (hasProtectedHandler(instruction)) {
                LinkedHashSet<IrValue> exceptionalNeeded =
                        cfg.exceptionalNeeded(instruction);
                LinkedHashSet<IrValue> exceptionalHeld =
                        new LinkedHashSet<>(held);
                exceptionDefinitions.stream()
                        .filter(value ->
                                ownershipClassifier.shouldEmitRelease(
                                        value,
                                        ownership))
                        .forEach(exceptionalHeld::add);
                exceptionalHeld.stream()
                        .filter(value ->
                                !exceptionalNeeded.contains(value))
                        .forEach(value -> releasesAt(
                                instructionReleases,
                                site).exceptional.add(value));
            }

            IrValue aliasSource =
                    ownershipClassifier.aliasSource(instruction)
                            .orElse(null);
            if (aliasSource != null) {
                held.remove(aliasSource);
            }
            instruction.result()
                    .filter(value -> value.type() == IrType.REFERENCE)
                    .filter(value -> ownershipClassifier.shouldEmitRelease(
                            value,
                            ownership))
                    .ifPresent(held::add);
            MutableReleaseSchedule releases =
                    instructionReleases.get(site);
            if (releases != null) {
                releases.normal.forEach(held::remove);
            }
        }
    }

    private LinkedHashSet<IrValue> exceptionDefinitions(
            IrInstruction instruction) {
        return instruction.exceptionSites().stream()
                .flatMap(exceptionSite ->
                        exceptionSite.exceptionValue().stream())
                .filter(value -> value.type() == IrType.REFERENCE)
                .collect(java.util.stream.Collectors.toCollection(
                        LinkedHashSet::new));
    }

    private boolean hasProtectedHandler(IrInstruction instruction) {
        return instruction.exceptionSites().stream()
                .anyMatch(site -> !site.handlers().isEmpty());
    }

    private boolean noInstructionRelease(
            String blockName,
            IrValue value,
            Map<
                            NativeLocalReferenceInstructionSite,
                            MutableReleaseSchedule>
                    releases) {
        return releases.entrySet().stream()
                .filter(entry ->
                        entry.getKey().blockName().equals(blockName))
                .noneMatch(entry ->
                        entry.getValue().normal.contains(value));
    }

    private boolean isActivationExit(IrBlock block) {
        IrTerminator terminator = block.terminator();
        return terminator.kind() == IrTerminatorKind.RETURN
                || (terminator.kind() == IrTerminatorKind.THROW
                        && terminator.value().isPresent()
                        && block.exceptionEdges().isEmpty());
    }

    private MutableReleaseSchedule releasesAt(
            Map<
                            NativeLocalReferenceInstructionSite,
                            MutableReleaseSchedule>
                    releases,
            NativeLocalReferenceInstructionSite site) {
        return releases.computeIfAbsent(
                site,
                ignored -> new MutableReleaseSchedule());
    }

    private Map<
                    NativeLocalReferenceInstructionSite,
                    NativeLocalReferenceReleaseSchedule>
            freezeInstructionReleases(
                    Map<
                                    NativeLocalReferenceInstructionSite,
                                    MutableReleaseSchedule>
                            mutable) {
        LinkedHashMap<
                        NativeLocalReferenceInstructionSite,
                        NativeLocalReferenceReleaseSchedule>
                result = new LinkedHashMap<>();
        mutable.forEach((site, schedule) -> result.put(
                site,
                new NativeLocalReferenceReleaseSchedule(
                        List.copyOf(schedule.normal),
                        List.copyOf(schedule.exceptional))));
        return Collections.unmodifiableMap(result);
    }

    private Map<String, List<IrValue>> freezeReleases(
            Map<String, LinkedHashSet<IrValue>> mutable) {
        LinkedHashMap<String, List<IrValue>> result =
                new LinkedHashMap<>();
        mutable.forEach((block, values) ->
                result.put(block, List.copyOf(values)));
        return Collections.unmodifiableMap(result);
    }

    private Map<NativeLocalReferenceNormalEdge, List<IrValue>>
            freezeEdgeReleases(
                    Map<
                                    NativeLocalReferenceNormalEdge,
                                    LinkedHashSet<IrValue>>
                            mutable) {
        LinkedHashMap<NativeLocalReferenceNormalEdge, List<IrValue>> result =
                new LinkedHashMap<>();
        mutable.forEach((edge, values) ->
                result.put(edge, List.copyOf(values)));
        return Collections.unmodifiableMap(result);
    }

    private static final class MutableReleaseSchedule {
        private final LinkedHashSet<IrValue> normal =
                new LinkedHashSet<>();
        private final LinkedHashSet<IrValue> exceptional =
                new LinkedHashSet<>();
    }
}
