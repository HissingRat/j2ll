package xyz.melodysky.ir.pass.protection;

import xyz.melodysky.ir.model.IrMethod;

/** Applies bounded region-local control-flow flattening to eligible IR. */
public final class ControlFlowFlatteningPass implements ProtectionPass {
    private final ControlFlowFlatteningRegionPlanner planner =
            new ControlFlowFlatteningRegionPlanner();
    private final ControlFlowFlatteningRegionRewriter rewriter =
            new ControlFlowFlatteningRegionRewriter();

    @Override
    public String name() {
        return "CONTROL_FLOW_FLATTENING";
    }

    @Override
    public boolean enabled(ProtectionConfig config) {
        return config.enabled() && config.controlFlowFlattening();
    }

    @Override
    public boolean applicable(IrMethod method) {
        return planner.plan(method, 0L).selected();
    }

    @Override
    public String skipReasonCode(IrMethod method) {
        return planner.plan(method, 0L).reasonCode();
    }

    @Override
    public boolean canRunAroundMonitorSensitiveBlocks() {
        return true;
    }

    @Override
    public IrMethod run(IrMethod method, ProtectionConfig config) {
        if (!enabled(config)) {
            return method;
        }
        ControlFlowFlatteningPlan plan = planner.plan(method, config.seed());
        return plan.selected()
                ? rewriter.rewrite(method, plan, config.seed())
                : method;
    }
}
