package xyz.melodysky.ir.pass.protection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrSwitchCase;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrTerminatorKind;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;

/** Rewrites an immutable CFF region plan without changing blocks outside it. */
final class ControlFlowFlatteningRegionRewriter {
    IrMethod rewrite(
            IrMethod method,
            ControlFlowFlatteningPlan plan,
            long seed) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(plan, "plan");
        if (!method.methodKey().equals(plan.methodKey())) {
            throw new IllegalArgumentException("CFF plan belongs to a different method");
        }
        if (!plan.selected()) {
            return method;
        }

        LinkedHashMap<String, IrBlock> blocksByName = new LinkedHashMap<>();
        method.blocks().forEach(block -> blocksByName.put(block.name(), block));
        String methodEntry = method.blocks().get(0).name();
        GeneratedNames names = GeneratedNames.forMethod(method, seed);
        LinkedHashMap<String, RegionRewrite> rewrites = new LinkedHashMap<>();
        for (ControlFlowFlatteningRegion region : plan.regions()) {
            if (region.contains(methodEntry) && !region.entryBlock().equals(methodEntry)) {
                throw new IllegalArgumentException(
                        "method entry may only be the entry of a CFF region");
            }
            RegionRewrite rewrite = prepare(method, region, blocksByName, names, seed);
            for (String member : region.memberBlocks()) {
                if (rewrites.put(member, rewrite) != null) {
                    throw new IllegalArgumentException("overlapping CFF region member " + member);
                }
            }
        }

        ArrayList<IrBlock> output = new ArrayList<>();
        for (IrBlock block : method.blocks()) {
            RegionRewrite rewrite = rewrites.get(block.name());
            if (rewrite == null) {
                output.add(block);
            } else if (block.name().equals(rewrite.region().entryBlock())) {
                output.addAll(rewrite.blocks());
            }
        }
        return new IrMethod(
                method.owner(),
                method.name(),
                method.descriptor(),
                method.returnType(),
                method.parameters(),
                output);
    }

    private RegionRewrite prepare(
            IrMethod method,
            ControlFlowFlatteningRegion region,
            Map<String, IrBlock> blocksByName,
            GeneratedNames names,
            long seed) {
        ProtectionRandom random = new ProtectionRandom(seed);
        String material = method.methodKey() + "\u0000" + region.regionId();
        String dispatcher = names.block(
                "cff_d_",
                "CONTROL_FLOW_FLATTENING:DISPATCHER",
                material);
        LinkedHashMap<String, String> bodies = new LinkedHashMap<>();
        for (String member : region.memberBlocks()) {
            if (!blocksByName.containsKey(member)) {
                throw new IllegalArgumentException("CFF region refers to missing block " + member);
            }
            bodies.put(member, names.block(
                    "cff_b_",
                    "CONTROL_FLOW_FLATTENING:BODY",
                    material + "\u0000" + member));
        }

        LinkedHashMap<String, String> transitions = new LinkedHashMap<>();
        for (String member : region.memberBlocks()) {
            IrTerminator terminator = blocksByName.get(member).terminator();
            if (terminator.kind() != IrTerminatorKind.BRANCH) {
                continue;
            }
            addTransitionIfInternal(
                    transitions,
                    terminator.trueTarget().orElseThrow(),
                    region,
                    names,
                    material);
            addTransitionIfInternal(
                    transitions,
                    terminator.falseTarget().orElseThrow(),
                    region,
                    names,
                    material);
        }

        IrValue dispatcherState = names.value(
                IrType.I32,
                "CONTROL_FLOW_FLATTENING:DISPATCHER_STATE",
                material);
        ArrayList<IrBlock> output = new ArrayList<>();
        output.add(entryShim(
                region.entryBlock(),
                dispatcher,
                region.state(region.entryBlock()).orElseThrow(),
                names,
                material));
        output.add(dispatcherBlock(
                method,
                region,
                dispatcher,
                dispatcherState,
                bodies,
                random));
        for (String member : region.memberBlocks()) {
            IrBlock original = blocksByName.get(member);
            output.add(bodyBlock(
                    original,
                    region,
                    dispatcher,
                    bodies.get(member),
                    transitions,
                    names,
                    material));
        }
        transitions.keySet().forEach(target -> appendTransition(
                output,
                target,
                region,
                dispatcher,
                transitions,
                names,
                material));
        return new RegionRewrite(region, List.copyOf(output));
    }

    private void addTransitionIfInternal(
            Map<String, String> transitions,
            String target,
            ControlFlowFlatteningRegion region,
            GeneratedNames names,
            String material) {
        if (region.contains(target)) {
            transitions.computeIfAbsent(target, ignored -> names.block(
                    "cff_t_",
                    "CONTROL_FLOW_FLATTENING:TRANSITION",
                    material + "\u0000" + target));
        }
    }

    private IrBlock entryShim(
            String originalEntry,
            String dispatcher,
            int initialState,
            GeneratedNames names,
            String material) {
        IrValue state = names.value(
                IrType.I32,
                "CONTROL_FLOW_FLATTENING:ENTRY_STATE",
                material);
        return new IrBlock(
                originalEntry,
                List.of(IrInstruction.constInt(state, initialState)),
                IrTerminator.gotoBlock(dispatcher, List.of(state)));
    }

    private IrBlock dispatcherBlock(
            IrMethod method,
            ControlFlowFlatteningRegion region,
            String dispatcher,
            IrValue state,
            Map<String, String> bodies,
            ProtectionRandom random) {
        String defaultBlock = region.memberBlocks().stream()
                .min(Comparator
                        .comparing((String block) -> random.token(
                                "CONTROL_FLOW_FLATTENING:REGION_DEFAULT_TARGET",
                                method.methodKey() + "\u0000" + region.regionId() + "\u0000" + block,
                                64))
                        .thenComparing(block -> block))
                .orElseThrow();
        List<IrSwitchCase> cases = region.memberBlocks().stream()
                .filter(block -> !block.equals(defaultBlock))
                .map(block -> new IrSwitchCase(
                        region.state(block).orElseThrow(),
                        bodies.get(block)))
                .toList();
        return new IrBlock(
                dispatcher,
                List.of(state),
                List.of(),
                IrTerminator.switchOn(state, bodies.get(defaultBlock), cases));
    }

    private IrBlock bodyBlock(
            IrBlock original,
            ControlFlowFlatteningRegion region,
            String dispatcher,
            String bodyName,
            Map<String, String> transitions,
            GeneratedNames names,
            String material) {
        ArrayList<IrInstruction> instructions = new ArrayList<>(original.instructions());
        IrTerminator terminator = original.terminator();
        if (terminator.kind() == IrTerminatorKind.GOTO) {
            String target = terminator.target().orElseThrow();
            if (region.contains(target)) {
                IrValue state = names.value(
                        IrType.I32,
                        "CONTROL_FLOW_FLATTENING:GOTO_STATE",
                        material + "\u0000" + original.name());
                instructions.add(IrInstruction.constInt(
                        state,
                        region.state(target).orElseThrow()));
                terminator = IrTerminator.gotoBlock(dispatcher, List.of(state));
            }
        } else if (terminator.kind() == IrTerminatorKind.BRANCH) {
            String trueTarget = terminator.trueTarget().orElseThrow();
            String falseTarget = terminator.falseTarget().orElseThrow();
            terminator = IrTerminator.branch(
                    terminator.condition().orElseThrow(),
                    region.contains(trueTarget)
                            ? transitions.get(trueTarget)
                            : trueTarget,
                    region.contains(falseTarget)
                            ? transitions.get(falseTarget)
                            : falseTarget);
        } else if (terminator.kind() != IrTerminatorKind.RETURN) {
            throw new IllegalStateException(
                    "unsupported terminator reached region CFF: " + terminator.kind());
        }
        return new IrBlock(
                bodyName,
                List.of(),
                original.exceptionCatchTypes(),
                original.exceptionEdges(),
                instructions,
                terminator);
    }

    private void appendTransition(
            List<IrBlock> output,
            String target,
            ControlFlowFlatteningRegion region,
            String dispatcher,
            Map<String, String> transitions,
            GeneratedNames names,
            String material) {
        String transition = transitions.get(target);
        if (transition == null) {
            return;
        }
        IrValue state = names.value(
                IrType.I32,
                "CONTROL_FLOW_FLATTENING:TRANSITION_STATE",
                material + "\u0000" + target);
        output.add(new IrBlock(
                transition,
                List.of(IrInstruction.constInt(
                        state,
                        region.state(target).orElseThrow())),
                IrTerminator.gotoBlock(dispatcher, List.of(state))));
    }

    private record RegionRewrite(
            ControlFlowFlatteningRegion region,
            List<IrBlock> blocks) {}

    private static final class GeneratedNames {
        private final ProtectionRandom random;
        private final Set<String> blockNames;
        private final Set<String> valueNames;

        private GeneratedNames(
                ProtectionRandom random,
                Set<String> blockNames,
                Set<String> valueNames) {
            this.random = random;
            this.blockNames = blockNames;
            this.valueNames = valueNames;
        }

        static GeneratedNames forMethod(IrMethod method, long seed) {
            LinkedHashSet<String> blockNames = method.blocks().stream()
                    .map(IrBlock::name)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            LinkedHashSet<String> valueNames = new LinkedHashSet<>();
            method.parameters().stream().map(IrValue::name).forEach(valueNames::add);
            for (IrBlock block : method.blocks()) {
                block.parameters().stream().map(IrValue::name).forEach(valueNames::add);
                block.instructions().forEach(instruction -> {
                    instruction.result().map(IrValue::name).ifPresent(valueNames::add);
                    instruction.exceptionSites().forEach(site -> site.exceptionValue()
                            .map(IrValue::name)
                            .ifPresent(valueNames::add));
                });
            }
            return new GeneratedNames(new ProtectionRandom(seed), blockNames, valueNames);
        }

        String block(String prefix, String purpose, String material) {
            return allocate(prefix, purpose, material, blockNames);
        }

        IrValue value(IrType type, String purpose, String material) {
            return new IrValue(
                    allocate("%j2ll_cff_", purpose, material, valueNames),
                    type);
        }

        private String allocate(
                String prefix,
                String purpose,
                String material,
                Set<String> occupied) {
            for (int attempt = 0; attempt < 256; attempt++) {
                String candidate = prefix + random.token(
                        purpose,
                        material + "\u0000" + attempt,
                        20);
                if (occupied.add(candidate)) {
                    return candidate;
                }
            }
            throw new IllegalStateException("unable to allocate collision-free CFF name");
        }
    }
}
