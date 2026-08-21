package xyz.melodysky.pipeline;

import java.util.Objects;
import xyz.melodysky.analysis.callgraph.CallGraph;
import xyz.melodysky.analysis.callgraph.DevirtualizationPlan;
import xyz.melodysky.analysis.runtime.RuntimeTypeResult;
import xyz.melodysky.analysis.runtime.ReachabilityResult;

/** Immutable whole-program call-target analysis consumed by later stages. */
public record ProgramCallGraphAnalysis(
        CallGraph callGraph,
        RuntimeTypeResult runtimeTypes,
        ReachabilityResult reachability,
        DevirtualizationPlan devirtualizationPlan,
        boolean rtaApplied) {
    public ProgramCallGraphAnalysis {
        Objects.requireNonNull(callGraph, "callGraph");
        Objects.requireNonNull(runtimeTypes, "runtimeTypes");
        Objects.requireNonNull(reachability, "reachability");
        Objects.requireNonNull(devirtualizationPlan, "devirtualizationPlan");
        java.util.Set<String> callSiteIds = callGraph.resolutions().stream()
                .map(resolution -> resolution.callSite().id())
                .collect(java.util.stream.Collectors.toSet());
        java.util.Set<String> decisionIds = devirtualizationPlan.decisions().stream()
                .map(decision -> decision.callSiteId())
                .collect(java.util.stream.Collectors.toSet());
        if (callSiteIds.size() != callGraph.resolutions().size()) {
            throw new IllegalArgumentException("call graph contains duplicate call-site ids");
        }
        if (!callSiteIds.equals(decisionIds)
                || decisionIds.size() != devirtualizationPlan.decisions().size()) {
            throw new IllegalArgumentException(
                    "devirtualization plan must cover every call-site id exactly once");
        }
        java.util.Map<String, xyz.melodysky.analysis.callgraph.CallResolution> resolutions =
                callGraph.resolutions().stream().collect(java.util.stream.Collectors.toMap(
                        resolution -> resolution.callSite().id(),
                        resolution -> resolution));
        for (var decision : devirtualizationPlan.decisions()) {
            var resolution = resolutions.get(decision.callSiteId());
            if (decision.originalKind() != resolution.callSite().kind()
                    || !decision.resolvedTargets().equals(resolution.targets())) {
                throw new IllegalArgumentException(
                        "devirtualization decision drifted from call resolution: "
                                + decision.callSiteId());
            }
        }
    }
}
