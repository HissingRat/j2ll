package xyz.melodysky.pipeline;

import java.util.Objects;

public record MethodEligibility(
        String owner,
        String name,
        String descriptor,
        String selector,
        boolean requested,
        MethodEligibilityStatus status,
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
        }
        if ((reasonCode == null) != (reason == null)) {
            throw new IllegalArgumentException("reasonCode and reason must be provided together");
        }
    }

    public static MethodEligibility requested(String owner, String name, String descriptor, String selector) {
        return new MethodEligibility(owner, name, descriptor, selector, true, null, null, null);
    }

    public static MethodEligibility ineligible(
            String owner,
            String name,
            String descriptor,
            String selector,
            String reasonCode,
            String reason) {
        return new MethodEligibility(
                owner, name, descriptor, selector, false, MethodEligibilityStatus.INELIGIBLE, reasonCode, reason);
    }

    public static MethodEligibility excluded(
            String owner,
            String name,
            String descriptor,
            String selector,
            String reasonCode,
            String reason) {
        return new MethodEligibility(
                owner, name, descriptor, selector, false, MethodEligibilityStatus.EXCLUDED, reasonCode, reason);
    }
}
