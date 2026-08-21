package xyz.melodysky.pipeline;

import java.util.Objects;
import java.util.List;
import java.util.Set;
import xyz.melodysky.analysis.callgraph.CallGraph;
import xyz.melodysky.analysis.callgraph.CallGraphBuilder;
import xyz.melodysky.analysis.callgraph.DevirtualizationPlanner;
import xyz.melodysky.analysis.hierarchy.AnalysisWorld;
import xyz.melodysky.analysis.hierarchy.ClassHierarchy;
import xyz.melodysky.analysis.runtime.EntryRootedRtaAnalyzer;
import xyz.melodysky.analysis.runtime.ReachabilityAnalyzer;
import xyz.melodysky.analysis.runtime.ReachabilityResult;
import xyz.melodysky.analysis.runtime.RuntimeAnalysisPipeline;
import xyz.melodysky.analysis.runtime.RuntimeTypeResult;
import xyz.melodysky.frontend.classfile.ParsedProgram;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.runtime.metadata.RuntimeMetadataIndex;

/** Builds the authoritative call graph and its devirtualization decisions. */
public final class ProgramCallGraphAnalysisCoordinator {
    public ProgramCallGraphAnalysis analyze(
            ParsedProgram program,
            ClassHierarchy hierarchy,
            RuntimeMetadataIndex metadataIndex,
            AnalysisWorld world,
            List<ParsedMethod> entryMethods) {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(hierarchy, "hierarchy");
        Objects.requireNonNull(metadataIndex, "metadataIndex");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(entryMethods, "entryMethods");

        CallGraph cha = new CallGraphBuilder().buildCha(
                program,
                hierarchy,
                metadataIndex);
        Set<String> entryMethodKeys = entryMethods.stream()
                .map(ParsedMethod::methodKey)
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
        boolean rtaApplied = world == AnalysisWorld.CLOSED_WORLD;
        CallGraph effective;
        RuntimeTypeResult runtimeTypes;
        ReachabilityResult reachability;
        if (rtaApplied) {
            var rta = new EntryRootedRtaAnalyzer().analyze(
                    program,
                    hierarchy,
                    cha,
                    entryMethods);
            effective = rta.callGraph();
            runtimeTypes = rta.runtimeTypes();
            reachability = rta.reachability();
        } else {
            effective = cha;
            reachability = new ReachabilityAnalyzer().analyze(
                    program,
                    cha,
                    entryMethodKeys);
            runtimeTypes = new RuntimeAnalysisPipeline().analyze(
                    program,
                    reachability.reachableMethodKeys(),
                    Set.of());
        }
        return new ProgramCallGraphAnalysis(
                effective,
                runtimeTypes,
                reachability,
                new DevirtualizationPlanner().plan(effective),
                rtaApplied);
    }
}
