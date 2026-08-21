package xyz.melodysky.pipeline;

import java.util.Objects;
import xyz.melodysky.analysis.callgraph.CallGraph;
import xyz.melodysky.analysis.callgraph.CallGraphBuilder;
import xyz.melodysky.analysis.callgraph.DevirtualizationPlanner;
import xyz.melodysky.analysis.hierarchy.AnalysisWorld;
import xyz.melodysky.analysis.hierarchy.ClassHierarchy;
import xyz.melodysky.analysis.runtime.RtaCallResolver;
import xyz.melodysky.analysis.runtime.RuntimeAnalysisPipeline;
import xyz.melodysky.analysis.runtime.RuntimeTypeResult;
import xyz.melodysky.frontend.classfile.ParsedProgram;
import xyz.melodysky.runtime.metadata.RuntimeMetadataIndex;

/** Builds the authoritative call graph and its devirtualization decisions. */
public final class ProgramCallGraphAnalysisCoordinator {
    public ProgramCallGraphAnalysis analyze(
            ParsedProgram program,
            ClassHierarchy hierarchy,
            RuntimeMetadataIndex metadataIndex,
            AnalysisWorld world) {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(hierarchy, "hierarchy");
        Objects.requireNonNull(metadataIndex, "metadataIndex");
        Objects.requireNonNull(world, "world");

        CallGraph cha = new CallGraphBuilder().buildCha(
                program,
                hierarchy,
                metadataIndex);
        RuntimeTypeResult runtimeTypes =
                new RuntimeAnalysisPipeline().analyze(program);
        boolean rtaApplied = world == AnalysisWorld.CLOSED_WORLD;
        CallGraph effective = rtaApplied
                ? refine(cha, hierarchy, runtimeTypes)
                : cha;
        return new ProgramCallGraphAnalysis(
                effective,
                runtimeTypes,
                new DevirtualizationPlanner().plan(effective),
                rtaApplied);
    }

    private CallGraph refine(
            CallGraph cha,
            ClassHierarchy hierarchy,
            RuntimeTypeResult runtimeTypes) {
        RtaCallResolver resolver = new RtaCallResolver(
                hierarchy,
                runtimeTypes);
        return new CallGraph(cha.resolutions().stream()
                .map(resolver::refine)
                .toList());
    }
}
