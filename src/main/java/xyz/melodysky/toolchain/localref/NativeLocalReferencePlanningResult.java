package xyz.melodysky.toolchain.localref;

import java.util.Objects;
import java.util.Optional;

/**
 * Fail-closed result of JNI local-reference lifetime planning.
 */
public record NativeLocalReferencePlanningResult(
        Optional<NativeLocalReferencePlan> plan,
        Optional<String> failureReason) {
    public NativeLocalReferencePlanningResult {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(failureReason, "failureReason");
        if (plan.isPresent() == failureReason.isPresent()) {
            throw new IllegalArgumentException(
                    "planning result must contain exactly one of plan or failureReason");
        }
    }

    public static NativeLocalReferencePlanningResult success(
            NativeLocalReferencePlan plan) {
        return new NativeLocalReferencePlanningResult(
                Optional.of(Objects.requireNonNull(plan, "plan")),
                Optional.empty());
    }

    public static NativeLocalReferencePlanningResult failure(String reason) {
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank()) {
            throw new IllegalArgumentException(
                    "failure reason must not be blank");
        }
        return new NativeLocalReferencePlanningResult(
                Optional.empty(),
                Optional.of(reason));
    }
}
