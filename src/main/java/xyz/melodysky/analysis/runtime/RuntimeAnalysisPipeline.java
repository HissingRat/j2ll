package xyz.melodysky.analysis.runtime;

import xyz.melodysky.frontend.classfile.ParsedProgram;

public final class RuntimeAnalysisPipeline {
    private final AllocationSiteCollector allocationSiteCollector = new AllocationSiteCollector();

    public RuntimeTypeResult analyze(ParsedProgram program) {
        return allocationSiteCollector.collect(program);
    }
}
