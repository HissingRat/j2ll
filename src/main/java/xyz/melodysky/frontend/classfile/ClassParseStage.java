package xyz.melodysky.frontend.classfile;

import xyz.melodysky.diagnostic.DiagnosticStage;
import xyz.melodysky.pipeline.PipelineContext;
import xyz.melodysky.pipeline.PipelineStage;
import xyz.melodysky.pipeline.StageResult;

public final class ClassParseStage implements PipelineStage<ClassFileSource, ClassParseResult> {
    private final AsmClassParser parser;

    public ClassParseStage() {
        this(new AsmClassParser());
    }

    public ClassParseStage(AsmClassParser parser) {
        this.parser = parser;
    }

    @Override
    public DiagnosticStage name() {
        return DiagnosticStage.PARSE;
    }

    @Override
    public StageResult<ClassParseResult> run(ClassFileSource input, PipelineContext context) {
        return parser.parseAll(input);
    }
}
