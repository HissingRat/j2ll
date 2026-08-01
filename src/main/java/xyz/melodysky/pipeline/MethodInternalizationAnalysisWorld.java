package xyz.melodysky.pipeline;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import xyz.melodysky.analysis.callgraph.CallGraph;
import xyz.melodysky.analysis.hierarchy.ClassHierarchy;
import xyz.melodysky.analysis.reflection.ReflectionPlan;
import xyz.melodysky.frontend.classfile.ParsedProgram;
import xyz.melodysky.runtime.metadata.RuntimeMetadataIndex;

/** Immutable combined input and configured-classpath analysis artifacts. */
public record MethodInternalizationAnalysisWorld(
        ParsedProgram combinedProgram,
        List<ParsedProgram> classpathPrograms,
        List<Path> analyzedClassPath,
        RuntimeMetadataIndex metadataIndex,
        ClassHierarchy hierarchy,
        CallGraph callGraph,
        ReflectionPlan reflectionPlan) {
    public MethodInternalizationAnalysisWorld {
        Objects.requireNonNull(combinedProgram, "combinedProgram");
        classpathPrograms = List.copyOf(
                Objects.requireNonNull(
                        classpathPrograms,
                        "classpathPrograms"));
        analyzedClassPath = Objects.requireNonNull(
                        analyzedClassPath,
                        "analyzedClassPath")
                .stream()
                .sorted()
                .toList();
        Objects.requireNonNull(metadataIndex, "metadataIndex");
        Objects.requireNonNull(hierarchy, "hierarchy");
        Objects.requireNonNull(callGraph, "callGraph");
        Objects.requireNonNull(reflectionPlan, "reflectionPlan");
    }
}
