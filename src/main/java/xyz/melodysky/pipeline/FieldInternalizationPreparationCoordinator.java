package xyz.melodysky.pipeline;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import xyz.melodysky.analysis.world.WholeProgramAnalysisPolicy;
import xyz.melodysky.config.ResolvedConfig;
import xyz.melodysky.frontend.classfile.ParsedProgram;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.packaging.MethodRewriteDecision;
import xyz.melodysky.packaging.NativeRegistrationPlan;
import xyz.melodysky.toolchain.NativeImplementationPlan;
import xyz.melodysky.toolchain.NativeImplementationPlanner;
import xyz.melodysky.toolchain.initializer.InitializerImplementationPlan;

/**
 * Prepares field internalization at the optimized-IR/protected-IR boundary.
 *
 * <p>The path probe intentionally runs against optimized IR before ordinary IR
 * protection. ConstantValue folding can introduce fresh SSA constants, so the
 * field rewrite must complete before constant protection and its coverage
 * reporting. The protected methods receive a newly planned final implementation
 * later in the mainline and remain subject to the existing final-plan validators.
 */
public final class FieldInternalizationPreparationCoordinator {
    private final NativeImplementationPlanner implementationPlanner;
    private final FieldInternalizationPipeline fieldInternalizationPipeline;

    public FieldInternalizationPreparationCoordinator(
            NativeImplementationPlanner implementationPlanner) {
        this.implementationPlanner = Objects.requireNonNull(
                implementationPlanner,
                "implementationPlanner");
        this.fieldInternalizationPipeline = new FieldInternalizationPipeline();
    }

    public FieldInternalizationPipelineResult run(
            ResolvedConfig config,
            ParsedProgram program,
            Map<String, IrMethod> optimizedMethods,
            NativeRegistrationPlan registrationPlan,
            List<MethodRewriteDecision> rewriteDecisions,
            Set<String> availableProgramMethodKeys,
            Map<String, InitializerImplementationPlan> initializerPlans,
            long seed,
            WholeProgramAnalysisPolicy wholeProgramPolicy) {
        NativeImplementationPlan pathProbe = implementationPlanner.plan(
                registrationPlan,
                rewriteDecisions,
                optimizedMethods,
                availableProgramMethodKeys,
                Set.of(),
                initializerPlans);
        return fieldInternalizationPipeline.run(
                config,
                program,
                optimizedMethods,
                pathProbe,
                seed,
                wholeProgramPolicy);
    }
}
