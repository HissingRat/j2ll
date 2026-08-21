package xyz.melodysky.analysis.runtime;

import java.util.Objects;
import xyz.melodysky.analysis.callgraph.CallGraph;

/** Fixed-point RTA graph, runtime types and reachability from one root set. */
public record EntryRootedRtaResult(
        CallGraph callGraph,
        RuntimeTypeResult runtimeTypes,
        ReachabilityResult reachability) {
    public EntryRootedRtaResult {
        Objects.requireNonNull(callGraph, "callGraph");
        Objects.requireNonNull(runtimeTypes, "runtimeTypes");
        Objects.requireNonNull(reachability, "reachability");
    }
}
