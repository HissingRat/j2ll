package xyz.melodysky.ir.pass;

import java.util.List;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.pipeline.StageResult;

/** Validated post-optimization lowering for whole JDK call combinations. */
public final class JdkPureNativeIntrinsicPipeline {
    private final OptimizationPipeline pipeline = new OptimizationPipeline(
            List.of(
                    new JdkPureNativeIntrinsicPass(),
                    new DeadInstructionEliminationPass()));

    public StageResult<IrMethod> run(IrMethod method) {
        return pipeline.run(method, PassContext.empty());
    }
}
