package xyz.melodysky.analysis.hierarchy;

import xyz.melodysky.diagnostic.DiagnosticStage;
import xyz.melodysky.frontend.classfile.ClassParseResult;
import xyz.melodysky.pipeline.PipelineContext;
import xyz.melodysky.pipeline.PipelineStage;
import xyz.melodysky.pipeline.StageResult;

public final class ClassHierarchyStage implements PipelineStage<ClassParseResult, ClassHierarchy> {
    private final ClassHierarchyBuilder builder;
    private final AnalysisWorld worldModel;

    public ClassHierarchyStage(AnalysisWorld worldModel) {
        this(new ClassHierarchyBuilder(), worldModel);
    }

    public ClassHierarchyStage(ClassHierarchyBuilder builder, AnalysisWorld worldModel) {
        this.builder = builder;
        this.worldModel = worldModel;
    }

    @Override
    public DiagnosticStage name() {
        return DiagnosticStage.HIERARCHY;
    }

    @Override
    public StageResult<ClassHierarchy> run(ClassParseResult input, PipelineContext context) {
        return builder.build(input.program(), worldModel);
    }
}
