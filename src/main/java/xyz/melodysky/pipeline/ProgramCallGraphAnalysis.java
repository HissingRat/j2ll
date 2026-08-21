package xyz.melodysky.pipeline;

import java.util.Objects;
import xyz.melodysky.analysis.callgraph.CallGraph;
import xyz.melodysky.analysis.callgraph.DevirtualizationPlan;
import xyz.melodysky.analysis.runtime.RuntimeTypeResult;

/** Immutable whole-program call-target analysis consumed by later stages. */
public record ProgramCallGraphAnalysis(
        CallGraph callGraph,
        RuntimeTypeResult runtimeTypes,
        DevirtualizationPlan devirtualizationPlan,
        boolean rtaApplied) {
    public ProgramCallGraphAnalysis {
        Objects.requireNonNull(callGraph, "callGraph");
        Objects.requireNonNull(runtimeTypes, "runtimeTypes");
        Objects.requireNonNull(devirtualizationPlan, "devirtualizationPlan");
    }
}
