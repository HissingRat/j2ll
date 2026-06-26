package xyz.melodysky.ir.pass;

import java.util.ArrayList;
import java.util.List;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticStage;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.validate.IrMethodValidator;
import xyz.melodysky.pipeline.StageResult;

public final class OptimizationPipeline {
    private final List<IrMethodPass> passes;
    private final IrMethodValidator validator;

    public OptimizationPipeline(List<IrMethodPass> passes) {
        this(passes, new IrMethodValidator());
    }

    public OptimizationPipeline(List<IrMethodPass> passes, IrMethodValidator validator) {
        this.passes = List.copyOf(passes);
        this.validator = validator;
    }

    public static OptimizationPipeline defaultPipeline() {
        return new OptimizationPipeline(List.of(
                new ConstantFoldingPass(),
                new DeadInstructionEliminationPass()));
    }

    public StageResult<IrMethod> run(IrMethod input, PassContext context) {
        IrMethod current = input;
        ArrayList<Diagnostic> diagnostics = new ArrayList<>();
        diagnostics.addAll(validator.validate(current));
        if (!diagnostics.isEmpty()) {
            return StageResult.failed(DiagnosticStage.OPTIMIZATION, diagnostics);
        }
        for (IrMethodPass pass : passes) {
            current = pass.run(current, context);
            diagnostics.addAll(validator.validate(current));
            if (!diagnostics.isEmpty()) {
                return StageResult.failed(DiagnosticStage.OPTIMIZATION, diagnostics);
            }
        }
        return StageResult.complete(DiagnosticStage.OPTIMIZATION, current, diagnostics);
    }
}
