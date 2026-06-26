package xyz.melodysky.pipeline;

import java.util.Objects;

public record MethodEligibility(
        String owner,
        String name,
        String descriptor,
        String selector,
        boolean requested,
        LoweringStatus status,
        String reasonCode,
        String reason) {
    public MethodEligibility {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(selector, "selector");
        if (requested && status != null) {
            throw new IllegalArgumentException("requested eligibility must not claim a final lowering status");
        }
        if (!requested) {
            Objects.requireNonNull(status, "status");
            if (status == LoweringStatus.LOWERED
                    || status == LoweringStatus.HALF_LOWERED
                    || status == LoweringStatus.FRONTEND_SKIPPED
                    || status == LoweringStatus.FAILED) {
                throw new IllegalArgumentException("eligibility cannot claim a lowering result before lowering runs");
            }
        }
        if ((reasonCode == null) != (reason == null)) {
            throw new IllegalArgumentException("reasonCode and reason must be provided together");
        }
    }

    public static MethodEligibility requested(String owner, String name, String descriptor, String selector) {
        return new MethodEligibility(owner, name, descriptor, selector, true, null, null, null);
    }

    public static MethodEligibility notApplicable(
            String owner,
            String name,
            String descriptor,
            String selector,
            String reasonCode,
            String reason) {
        return new MethodEligibility(
                owner, name, descriptor, selector, false, LoweringStatus.NOT_APPLICABLE, reasonCode, reason);
    }

    public static MethodEligibility excluded(
            String owner,
            String name,
            String descriptor,
            String selector,
            String reasonCode,
            String reason) {
        return new MethodEligibility(
                owner, name, descriptor, selector, false, LoweringStatus.EXCLUDED, reasonCode, reason);
    }
}
