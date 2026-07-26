package xyz.melodysky.ir.pass.protection;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import xyz.melodysky.ir.model.IrOpcode;

public record MethodInliningPlan(List<MethodInliningCandidate> candidates) {
    public MethodInliningPlan {
        candidates = candidates.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
        HashSet<String> edgeKeys = new HashSet<>();
        for (MethodInliningCandidate candidate : candidates) {
            String edgeKey = candidate.callerMethodKey() + "\u0000"
                    + candidate.calleeMethodKey() + "\u0000"
                    + candidate.invokeOpcode();
            if (!edgeKeys.add(edgeKey)) {
                throw new IllegalArgumentException("conflicting method inlining facts for one direct edge");
            }
        }
    }

    public static MethodInliningPlan empty() {
        return new MethodInliningPlan(List.of());
    }

    public Optional<MethodInliningCandidate> candidate(
            String callerMethodKey,
            String calleeMethodKey,
            IrOpcode invokeOpcode) {
        return candidates.stream()
                .filter(candidate -> candidate.callerMethodKey().equals(callerMethodKey)
                        && candidate.calleeMethodKey().equals(calleeMethodKey)
                        && candidate.invokeOpcode() == invokeOpcode)
                .findFirst();
    }
}
