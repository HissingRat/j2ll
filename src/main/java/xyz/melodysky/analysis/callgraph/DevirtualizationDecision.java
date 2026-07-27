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
    }
}
