package xyz.melodysky.ir.pass;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;

/** Replaces approved carriers with the original active use followed by its acquire marker. */
public final class ActiveUseCarrierFusionPass implements IrMethodPass {
    private final Set<String> possibleDirectNativeCalls;
    private final ActiveUseCarrierFusionPlanner planner;

    public ActiveUseCarrierFusionPass(Set<String> possibleDirectNativeCalls) {
        this(possibleDirectNativeCalls, new ActiveUseCarrierFusionPlanner());
    }

    ActiveUseCarrierFusionPass(
            Set<String> possibleDirectNativeCalls,
            ActiveUseCarrierFusionPlanner planner) {
        this.possibleDirectNativeCalls = Set.copyOf(Objects.requireNonNull(
                possibleDirectNativeCalls,
                "possibleDirectNativeCalls"));
        this.planner = Objects.requireNonNull(planner, "planner");
    }

    @Override
    public String name() {
        return "activeUseCarrierFusion";
    }

    @Override
    public IrMethod run(IrMethod method, PassContext context) {
        ActiveUseCarrierFusionPlan plan = planner.plan(
                method,
                possibleDirectNativeCalls);
        if (plan.isEmpty()) {
            return method;
        }
        Map<String, Map<Integer, ActiveUseCarrierFusionPlan.Site>> sitesByBlock =
                sitesByBlock(plan);
        ArrayList<IrBlock> blocks = new ArrayList<>(method.blocks().size());
        for (IrBlock block : method.blocks()) {
            Map<Integer, ActiveUseCarrierFusionPlan.Site> sites = sitesByBlock
                    .getOrDefault(block.name(), Map.of());
            if (sites.isEmpty()) {
                blocks.add(block);
                continue;
            }
            ArrayList<IrInstruction> instructions = new ArrayList<>();
            for (int index = 0; index < block.instructions().size(); index++) {
                ActiveUseCarrierFusionPlan.Site site = sites.get(index);
                if (site == null) {
                    instructions.add(block.instructions().get(index));
                    continue;
                }
                instructions.add(block.instructions().get(site.activeUseIndex()));
                instructions.add(IrInstruction.operation(
                        Optional.empty(),
                        IrOpcode.CLASS_INIT_ACTIVE_USE,
                        List.of(),
                        site.classSymbol()));
                index = site.activeUseIndex();
            }
            blocks.add(new IrBlock(
                    block.name(),
                    block.parameters(),
                    block.exceptionCatchTypes(),
                    block.exceptionEdges(),
                    instructions,
                    block.terminator()));
        }
        return new IrMethod(
                method.owner(),
                method.name(),
                method.descriptor(),
                method.returnType(),
                method.parameters(),
                blocks);
    }

    private Map<String, Map<Integer, ActiveUseCarrierFusionPlan.Site>> sitesByBlock(
            ActiveUseCarrierFusionPlan plan) {
        HashMap<String, Map<Integer, ActiveUseCarrierFusionPlan.Site>> result =
                new HashMap<>();
        HashMap<String, HashMap<Integer, ActiveUseCarrierFusionPlan.Site>> mutable =
                new HashMap<>();
        for (ActiveUseCarrierFusionPlan.Site site : plan.sites()) {
            mutable.computeIfAbsent(site.blockName(), ignored -> new HashMap<>())
                    .put(site.carrierStartIndex(), site);
        }
        mutable.forEach((block, sites) -> result.put(block, Map.copyOf(sites)));
        return Map.copyOf(result);
    }
}
