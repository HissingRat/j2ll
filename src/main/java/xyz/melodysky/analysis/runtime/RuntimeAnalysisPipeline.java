package xyz.melodysky.analysis.runtime;

import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import xyz.melodysky.frontend.classfile.ParsedProgram;

public final class RuntimeAnalysisPipeline {
    private final AllocationSiteCollector allocationSiteCollector = new AllocationSiteCollector();

    public RuntimeTypeResult analyze(
            ParsedProgram program,
            Set<String> reachableMethodKeys,
            Set<String> seedTypes) {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(reachableMethodKeys, "reachableMethodKeys");
        TreeSet<String> instantiated = new TreeSet<>(
                Objects.requireNonNull(seedTypes, "seedTypes"));
        RuntimeTypeResult collected = allocationSiteCollector.collect(
                program,
                reachableMethodKeys);
        instantiated.addAll(collected.instantiatedClasses());
        return new RuntimeTypeResult(
                instantiated,
                collected.conservative(),
                collected.allocationSites());
    }
}
