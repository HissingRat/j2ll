package xyz.melodysky.analysis.callgraph;

import java.util.List;
import java.util.Optional;

public final class DevirtualizationPlanner {
    public DevirtualizationPlan plan(CallGraph callGraph) {
        return new DevirtualizationPlan(callGraph.resolutions().stream()
                .map(this::decisionFor)
                .toList());
    }

    private DevirtualizationDecision decisionFor(CallResolution resolution) {
        List<CallTarget> knownTargets = resolution.targets().stream()
                .filter(target -> !target.unknownExternal())
                .toList();
        boolean hasUnknown = resolution.hasUnknownTarget();
        if (!hasUnknown && knownTargets.size() == 1) {
            return new DevirtualizationDecision(
                    resolution.callSite().id(),
                    resolution.callSite().kind(),
                    resolution.targets(),
                    Optional.of(knownTargets.get(0)),
                    false,
                    "SINGLE_TARGET",
                    false);
        }
        String reason = hasUnknown ? "UNKNOWN_EXTERNAL_TARGET" : "MULTIPLE_TARGETS";
        return new DevirtualizationDecision(
                resolution.callSite().id(),
                resolution.callSite().kind(),
                resolution.targets(),
                Optional.empty(),
                true,
                reason,
                hasUnknown || resolution.callSite().kind().dispatchesDynamically());
    }
}
