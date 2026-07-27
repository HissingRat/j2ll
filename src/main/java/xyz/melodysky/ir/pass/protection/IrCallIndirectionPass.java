package xyz.melodysky.ir.pass.protection;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import xyz.melodysky.ir.model.IrCallIndirectionMode;
import xyz.melodysky.ir.model.IrProgram;

/**
 * Program-level IR call-indirection pass.
 *
 * <p>The caller supplies both call-resolution facts and the exact set of
 * methods proven to finish on {@code LLVM_NATIVE_PATH}. This pass never
 * upgrades unresolved, non-native, or helper-sensitive calls on its own.</p>
 */
public final class IrCallIndirectionPass {
    private final IrCallIndirectionPlanner planner;
    private final IrCallIndirectionRewriter rewriter;
    private final IrCallIndirectionValidator validator;

    public IrCallIndirectionPass() {
        this(
                new IrCallIndirectionPlanner(),
                new IrCallIndirectionRewriter(),
                new IrCallIndirectionValidator());
    }

    IrCallIndirectionPass(
            IrCallIndirectionPlanner planner,
            IrCallIndirectionRewriter rewriter,
            IrCallIndirectionValidator validator) {
        this.planner = Objects.requireNonNull(planner, "planner");
        this.rewriter = Objects.requireNonNull(rewriter, "rewriter");
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    public IrCallIndirectionResult run(
            IrProgram program,
            IrDirectCallFacts directCallFacts,
            IrNativeDirectTargets nativeDirectTargets,
            IrCallIndirectionMode mode,
            long seed,
            boolean enabled) {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(directCallFacts, "directCallFacts");
        Objects.requireNonNull(nativeDirectTargets, "nativeDirectTargets");
        Objects.requireNonNull(mode, "mode");
        if (!enabled) {
            return new IrCallIndirectionResult(
                    program,
                    Optional.empty(),
                    List.of(),
                    List.of(),
                    IrCallIndirectionReasons.DISABLED);
        }

        IrCallIndirectionPlanningResult planning =
                planner.plan(program, directCallFacts, nativeDirectTargets, mode, seed);
        if (planning.plan().isEmpty()) {
            return new IrCallIndirectionResult(
                    program,
                    Optional.empty(),
                    planning.skippedSites(),
                    List.of(),
                    IrCallIndirectionReasons.NO_CANDIDATE);
        }

        IrCallIndirectionPlan plan = planning.plan().orElseThrow();
        IrProgram rewritten = rewriter.rewrite(program, plan);
        var diagnostics = validator.validate(rewritten, plan, nativeDirectTargets);
        if (!diagnostics.isEmpty()) {
            return new IrCallIndirectionResult(
                    program,
                    Optional.empty(),
                    planning.skippedSites(),
                    diagnostics,
                    IrCallIndirectionReasons.VALIDATION_FAILED);
        }
        return new IrCallIndirectionResult(
                rewritten,
                Optional.of(plan),
                planning.skippedSites(),
                List.of(),
                mode == IrCallIndirectionMode.TABLE
                        ? IrCallIndirectionReasons.TABLE
                        : IrCallIndirectionReasons.DISPATCHER);
    }
}
