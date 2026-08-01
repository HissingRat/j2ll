package xyz.melodysky.pipeline;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import xyz.melodysky.analysis.callgraph.CallGraph;
import xyz.melodysky.analysis.callgraph.CallGraphBuilder;
import xyz.melodysky.analysis.hierarchy.AnalysisWorld;
import xyz.melodysky.analysis.hierarchy.ClassHierarchy;
import xyz.melodysky.analysis.hierarchy.ClassHierarchyBuilder;
import xyz.melodysky.analysis.reflection.ReflectionPlan;
import xyz.melodysky.analysis.reflection.StaticReflectionResolver;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticCode;
import xyz.melodysky.diagnostic.DiagnosticLocation;
import xyz.melodysky.diagnostic.DiagnosticSeverity;
import xyz.melodysky.diagnostic.DiagnosticStage;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassParseDiagnostics;
import xyz.melodysky.frontend.classfile.ClassParseResult;
import xyz.melodysky.frontend.classfile.DirectoryClassFileSource;
import xyz.melodysky.frontend.classfile.JarClassFileSource;
import xyz.melodysky.frontend.classfile.ParsedClass;
import xyz.melodysky.frontend.classfile.ParsedProgram;
import xyz.melodysky.frontend.classfile.ParsedProgramValidator;
import xyz.melodysky.runtime.metadata.RuntimeMetadataIndex;
import xyz.melodysky.runtime.metadata.RuntimeMetadataIndexBuilder;

/** Builds the actual CLOSED_WORLD facts used by method internalization. */
public final class MethodInternalizationAnalysisWorldBuilder {
    public static final DiagnosticCode ANALYSIS_WORLD_BUILD_FAILED =
            DiagnosticCode.of(
                    "METHOD_INTERNALIZATION_ANALYSIS_WORLD_BUILD_FAILED");

    public Result build(
            ParsedProgram inputProgram,
            List<Path> classPath) {
        Objects.requireNonNull(inputProgram, "inputProgram");
        List<Path> stableClassPath = Objects.requireNonNull(
                        classPath,
                        "classPath")
                .stream()
                .filter(Objects::nonNull)
                .sorted()
                .toList();
        ArrayList<Diagnostic> diagnostics = new ArrayList<>();
        ArrayList<ParsedProgram> classpathPrograms = new ArrayList<>();
        AsmClassParser parser = new AsmClassParser();
        boolean parseComplete = true;
        for (Path entry : stableClassPath) {
            StageResult<ClassParseResult> parsed = Files.isDirectory(entry)
                    ? parser.parseAll(new DirectoryClassFileSource(entry))
                    : parser.parseAll(new JarClassFileSource(entry));
            diagnostics.addAll(parsed.diagnostics());
            if (parsed.artifact().isEmpty()) {
                parseComplete = false;
            } else {
                classpathPrograms.add(
                        parsed.artifact().orElseThrow().program());
            }
        }
        if (!parseComplete) {
            return Result.failed(diagnostics);
        }

        ParsedProgram combined = combine(
                inputProgram,
                classpathPrograms,
                diagnostics);
        diagnostics.addAll(new ParsedProgramValidator().validate(
                new ClassParseResult(combined)));
        if (hasErrors(diagnostics)) {
            return Result.failed(diagnostics);
        }

        StageResult<RuntimeMetadataIndex> metadataResult;
        try {
            metadataResult =
                    new RuntimeMetadataIndexBuilder().build(combined);
        } catch (RuntimeException exception) {
            diagnostics.add(buildFailure(
                    DiagnosticStage.RUNTIME_ANALYSIS,
                    "runtime metadata",
                    exception));
            return Result.failed(diagnostics);
        }
        diagnostics.addAll(metadataResult.diagnostics());
        if (metadataResult.artifact().isEmpty()) {
            return Result.failed(diagnostics);
        }
        RuntimeMetadataIndex metadataIndex =
                metadataResult.artifact().orElseThrow();

        StageResult<ClassHierarchy> hierarchyResult;
        try {
            hierarchyResult = new ClassHierarchyBuilder().build(
                    combined,
                    AnalysisWorld.CLOSED_WORLD);
        } catch (RuntimeException exception) {
            diagnostics.add(buildFailure(
                    DiagnosticStage.HIERARCHY,
                    "class hierarchy",
                    exception));
            return Result.failed(diagnostics);
        }
        diagnostics.addAll(hierarchyResult.diagnostics());
        if (hierarchyResult.artifact().isEmpty()) {
            return Result.failed(diagnostics);
        }
        ClassHierarchy hierarchy =
                hierarchyResult.artifact().orElseThrow();

        ReflectionPlan reflectionPlan;
        try {
            reflectionPlan = new StaticReflectionResolver().resolve(
                    combined,
                    metadataIndex);
        } catch (RuntimeException exception) {
            diagnostics.add(buildFailure(
                    DiagnosticStage.RUNTIME_ANALYSIS,
                    "reflection plan",
                    exception));
            return Result.failed(diagnostics);
        }
        CallGraph callGraph;
        try {
            callGraph = new CallGraphBuilder().buildCha(
                    combined,
                    hierarchy,
                    metadataIndex);
        } catch (RuntimeException exception) {
            diagnostics.add(buildFailure(
                    DiagnosticStage.CALL_GRAPH,
                    "call graph",
                    exception));
            return Result.failed(diagnostics);
        }
        return Result.complete(
                new MethodInternalizationAnalysisWorld(
                        combined,
                        classpathPrograms,
                        stableClassPath,
                        metadataIndex,
                        hierarchy,
                        callGraph,
                        reflectionPlan),
                diagnostics);
    }

    private ParsedProgram combine(
            ParsedProgram inputProgram,
            List<ParsedProgram> classpathPrograms,
            List<Diagnostic> diagnostics) {
        LinkedHashMap<String, ParsedClass> classes = new LinkedHashMap<>();
        addClasses(classes, inputProgram, diagnostics);
        for (ParsedProgram program : classpathPrograms) {
            addClasses(classes, program, diagnostics);
        }
        return new ParsedProgram(List.copyOf(classes.values()));
    }

    private void addClasses(
            LinkedHashMap<String, ParsedClass> classes,
            ParsedProgram program,
            List<Diagnostic> diagnostics) {
        for (ParsedClass parsedClass : program.classes()) {
            ParsedClass previous = classes.putIfAbsent(
                    parsedClass.internalName(),
                    parsedClass);
            if (previous != null) {
                diagnostics.add(Diagnostic.error(
                                DiagnosticStage.VALIDATION,
                                ClassParseDiagnostics.DUPLICATE_CLASS,
                                "duplicate class in method-internalization "
                                        + "analysis world: "
                                        + parsedClass.internalName())
                        .at(DiagnosticLocation.classLocation(
                                parsedClass.internalName())));
            }
        }
    }

    private boolean hasErrors(List<Diagnostic> diagnostics) {
        return diagnostics.stream().anyMatch(diagnostic ->
                diagnostic.severity() == DiagnosticSeverity.ERROR);
    }

    private Diagnostic buildFailure(
            DiagnosticStage stage,
            String component,
            RuntimeException exception) {
        String detail = exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
        return Diagnostic.error(
                stage,
                ANALYSIS_WORLD_BUILD_FAILED,
                "failed to build method-internalization CLOSED_WORLD "
                        + component
                        + ": "
                        + detail);
    }

    public record Result(
            Optional<MethodInternalizationAnalysisWorld> world,
            List<Diagnostic> diagnostics) {
        public Result {
            Objects.requireNonNull(world, "world");
            diagnostics = Objects.requireNonNull(
                            diagnostics,
                            "diagnostics")
                    .stream()
                    .filter(Objects::nonNull)
                    .sorted()
                    .distinct()
                    .toList();
        }

        public static Result complete(
                MethodInternalizationAnalysisWorld world,
                List<Diagnostic> diagnostics) {
            return new Result(Optional.of(world), diagnostics);
        }

        public static Result failed(List<Diagnostic> diagnostics) {
            return new Result(Optional.empty(), diagnostics);
        }

        public boolean complete() {
            return world.isPresent()
                    && diagnostics.stream().noneMatch(diagnostic ->
                            diagnostic.severity()
                                    == DiagnosticSeverity.ERROR);
        }
    }
}
