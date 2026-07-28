package xyz.melodysky.toolchain.localref;

import java.util.Objects;
import java.util.Optional;
import xyz.melodysky.ir.model.IrMethod;

/**
 * Orchestrates ownership classification, CFG liveness and release scheduling.
 */
public final class NativeLocalReferencePlanner {
    private final NativeLocalReferenceOwnershipClassifier ownershipClassifier =
            new NativeLocalReferenceOwnershipClassifier();

    public NativeLocalReferencePlanningResult plan(IrMethod method) {
        Objects.requireNonNull(method, "method");
        if (method.blocks().isEmpty()) {
            return NativeLocalReferencePlanningResult.failure(
                    "local-reference planning requires a non-empty CFG");
        }

        NativeLocalReferenceOwnershipClassifier.Classification ownership =
                ownershipClassifier.classify(method);
        if (ownership.failureReason().isPresent()) {
            return NativeLocalReferencePlanningResult.failure(
                    ownership.failureReason().orElseThrow());
        }

        NativeLocalReferenceCfgFacts cfg =
                NativeLocalReferenceCfgFacts.analyze(method);
        Optional<String> handlerFailure =
                cfg.validateUniformProtectedHandlerNeeds(method);
        if (handlerFailure.isPresent()) {
            return NativeLocalReferencePlanningResult.failure(
                    handlerFailure.orElseThrow());
        }
        NativeLocalReferencePlan plan =
                new NativeLocalReferenceReleaseScheduler(
                        ownershipClassifier)
                        .schedule(
                                method,
                                ownership.ownershipByValue(),
                                cfg);
        Optional<String> failure =
                new NativeLocalReferencePlanValidator().validate(
                        method,
                        plan);
        return failure
                .map(NativeLocalReferencePlanningResult::failure)
                .orElseGet(() ->
                        NativeLocalReferencePlanningResult.success(plan));
    }
}
