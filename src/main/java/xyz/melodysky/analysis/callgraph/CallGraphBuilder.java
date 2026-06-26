package xyz.melodysky.analysis.callgraph;

import java.util.List;
import xyz.melodysky.analysis.hierarchy.ClassHierarchy;
import xyz.melodysky.frontend.classfile.ParsedProgram;
import xyz.melodysky.runtime.metadata.RuntimeMetadataIndex;
import xyz.melodysky.runtime.metadata.RuntimeMetadataIndexBuilder;

public final class CallGraphBuilder {
    private final CallSiteCollector collector = new CallSiteCollector();

    public CallGraph buildCha(ParsedProgram program, ClassHierarchy hierarchy) {
        RuntimeMetadataIndex metadataIndex = new RuntimeMetadataIndexBuilder().build(program).artifact().orElseThrow();
        return buildCha(program, hierarchy, metadataIndex);
    }

    public CallGraph buildCha(ParsedProgram program, ClassHierarchy hierarchy, RuntimeMetadataIndex metadataIndex) {
        ChaCallResolver resolver = new ChaCallResolver(hierarchy);
        List<CallResolution> resolutions = collector.collect(program, metadataIndex).stream()
                .map(resolver::resolve)
                .toList();
        return new CallGraph(resolutions);
    }
}
