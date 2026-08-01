package xyz.melodysky.analysis.method;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import xyz.melodysky.analysis.world.WholeProgramAnalysisScope;

public record NativeMethodInternalizationPlan(
        boolean enabled,
        WholeProgramAnalysisScope analysisScope,
        List<NativeMethodInternalizationDecision> decisions) {
    public NativeMethodInternalizationPlan {
        Objects.requireNonNull(analysisScope, "analysisScope");
        decisions = Objects.requireNonNull(decisions, "decisions")
                .stream()
                .filter(Objects::nonNull)
                .sorted()
                .toList();
        long distinct = decisions.stream()
                .map(decision -> decision.method().methodKey())
                .distinct()
                .count();
        if (distinct != decisions.size()) {
            throw new IllegalArgumentException(
                    "duplicate method internalization decision");
        }
    }

    public static NativeMethodInternalizationPlan disabled() {
        return new NativeMethodInternalizationPlan(
                false,
                WholeProgramAnalysisScope.NOT_REQUIRED,
                List.of());
    }

    public Set<String> internalizedMethodKeys() {
        return decisions.stream()
                .filter(NativeMethodInternalizationDecision::internalized)
                .map(decision -> decision.method().methodKey())
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isInternalized(String methodKey) {
        return internalizedMethodKeys().contains(methodKey);
    }

    public Optional<NativeMethodInternalizationDecision> decisionFor(
            String methodKey) {
        return decisions.stream()
                .filter(decision ->
                        decision.method().methodKey().equals(methodKey))
                .findFirst();
    }

    public int internalizedCount() {
        return (int) decisions.stream()
                .filter(NativeMethodInternalizationDecision::internalized)
                .count();
    }
}
