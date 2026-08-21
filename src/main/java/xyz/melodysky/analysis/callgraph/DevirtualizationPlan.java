package xyz.melodysky.analysis.callgraph;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record DevirtualizationPlan(List<DevirtualizationDecision> decisions) {
    public DevirtualizationPlan {
        decisions = decisions.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(DevirtualizationDecision::callSiteId))
                .toList();
        LinkedHashMap<String, DevirtualizationDecision> indexed = new LinkedHashMap<>();
        for (DevirtualizationDecision decision : decisions) {
            if (indexed.put(decision.callSiteId(), decision) != null) {
                throw new IllegalArgumentException(
                        "duplicate devirtualization call-site id: " + decision.callSiteId());
            }
        }
    }

    public Optional<DevirtualizationDecision> decisionFor(String callSiteId) {
        Objects.requireNonNull(callSiteId, "callSiteId");
        return decisions.stream()
                .filter(decision -> decision.callSiteId().equals(callSiteId))
                .findFirst();
    }
}
