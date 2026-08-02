package xyz.melodysky.analysis.method;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/** Final immutable map from logical internal methods to physical emission. */
public record NativeOnlyMethodCoalescingPlan(
        List<NativeOnlyMethodCoalescingDecision> decisions) {
    public NativeOnlyMethodCoalescingPlan {
        decisions = Objects.requireNonNull(decisions, "decisions").stream()
                .filter(Objects::nonNull)
                .sorted()
                .toList();
        long distinct = decisions.stream()
                .map(NativeOnlyMethodCoalescingDecision::calleeMethodKey)
                .distinct()
                .count();
        if (distinct != decisions.size()) {
            throw new IllegalArgumentException(
                    "duplicate native-only coalescing decision");
        }
    }

    public static NativeOnlyMethodCoalescingPlan empty() {
        return new NativeOnlyMethodCoalescingPlan(List.of());
    }

    public Map<String, String> coalescedIntoByMethod() {
        return decisions.stream()
                .filter(NativeOnlyMethodCoalescingDecision::coalesced)
                .collect(Collectors.toUnmodifiableMap(
                        NativeOnlyMethodCoalescingDecision::calleeMethodKey,
                        decision -> decision.callerMethodKey().orElseThrow()));
    }

    public Optional<String> coalescedInto(String methodKey) {
        return Optional.ofNullable(coalescedIntoByMethod().get(methodKey));
    }

    public int coalescedCount() {
        return coalescedIntoByMethod().size();
    }
}
