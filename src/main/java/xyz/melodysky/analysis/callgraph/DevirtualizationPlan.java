package xyz.melodysky.analysis.callgraph;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record DevirtualizationPlan(List<DevirtualizationDecision> decisions) {
    public DevirtualizationPlan {
        decisions = decisions.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(DevirtualizationDecision::callSiteId))
                .toList();
    }

    public Optional<DevirtualizationDecision> decisionFor(String callSiteId) {
        return decisions.stream()
                .filter(decision -> decision.callSiteId().equals(callSiteId))
                .findFirst();
    }
}
