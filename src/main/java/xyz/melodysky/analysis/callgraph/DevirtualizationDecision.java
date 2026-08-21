package xyz.melodysky.analysis.callgraph;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record DevirtualizationDecision(
        String callSiteId,
        InvokeKind originalKind,
        List<CallTarget> resolvedTargets,
        Optional<CallTarget> directTarget,
        boolean directNativeTargetUnavailable,
        String reason,
        boolean jvmDispatchRequired) {
    public DevirtualizationDecision {
        Objects.requireNonNull(callSiteId, "callSiteId");
        Objects.requireNonNull(originalKind, "originalKind");
        resolvedTargets = List.copyOf(Objects.requireNonNull(resolvedTargets, "resolvedTargets"));
        Objects.requireNonNull(directTarget, "directTarget");
        Objects.requireNonNull(reason, "reason");
        List<CallTarget> stableTargets = resolvedTargets;
        directTarget.ifPresent(target -> {
            if (!stableTargets.contains(target)) {
                throw new IllegalArgumentException(
                        "directTarget must be present in resolvedTargets");
            }
            if (target.unknownExternal()) {
                throw new IllegalArgumentException(
                        "directTarget must identify a known program method");
            }
        });
        if (directTarget.isPresent()
                && (directNativeTargetUnavailable || jvmDispatchRequired)) {
            throw new IllegalArgumentException(
                    "direct devirtualization cannot require JVM dispatch or report an unavailable target");
        }
        if (directTarget.isEmpty() && !directNativeTargetUnavailable) {
            throw new IllegalArgumentException(
                    "non-direct devirtualization decision must report unavailable direct target");
        }
    }
}
