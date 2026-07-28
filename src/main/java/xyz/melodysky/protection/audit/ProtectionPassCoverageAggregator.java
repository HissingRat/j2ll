package xyz.melodysky.protection.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/** Aggregates explicit pass facts without guessing missing applicability. */
public final class ProtectionPassCoverageAggregator {
    public ProtectionCoverageSnapshot aggregate(
            List<ProtectionPassCoverageFact> input) {
        Objects.requireNonNull(input, "input");
        TreeMap<String, ProtectionPassCoverageFact> unique = new TreeMap<>();
        for (ProtectionPassCoverageFact fact : input) {
            Objects.requireNonNull(fact, "protection pass coverage fact");
            ProtectionPassCoverageFact duplicate =
                    unique.putIfAbsent(fact.factKey(), fact);
            if (duplicate != null) {
                throw new IllegalArgumentException(
                        "duplicate protection pass coverage fact: "
                                + fact.factKey());
            }
        }
        TreeMap<String, List<ProtectionPassCoverageFact>> groups =
                new TreeMap<>();
        unique.values().forEach(fact -> groups
                .computeIfAbsent(fact.passKey(), ignored -> new ArrayList<>())
                .add(fact));
        List<ProtectionPassCoverageRow> rows = groups.values().stream()
                .map(this::row)
                .toList();
        List<ProtectionPassCoverageFact> facts =
                List.copyOf(unique.values());
        Counts total = counts(facts);
        return new ProtectionCoverageSnapshot(
                facts.size(),
                total.requested,
                total.applicable,
                total.notApplicable,
                total.unknown,
                total.affected,
                rate(total.affected, total.requestedApplicable),
                rows,
                facts);
    }

    private ProtectionPassCoverageRow row(
            List<ProtectionPassCoverageFact> facts) {
        ProtectionPassCoverageFact first = facts.get(0);
        Counts counts = counts(facts);
        TreeMap<String, Integer> statuses = new TreeMap<>();
        TreeMap<String, Integer> reasons = new TreeMap<>();
        facts.forEach(fact -> {
            statuses.merge(fact.status(), 1, Integer::sum);
            reasons.merge(fact.reasonCode(), 1, Integer::sum);
        });
        return new ProtectionPassCoverageRow(
                first.layer(),
                first.passName(),
                facts.size(),
                counts.requested,
                counts.applicable,
                counts.notApplicable,
                counts.unknown,
                counts.affected,
                rate(counts.affected, counts.requestedApplicable),
                statuses,
                reasons);
    }

    private Counts counts(List<ProtectionPassCoverageFact> facts) {
        Counts counts = new Counts();
        for (ProtectionPassCoverageFact fact : facts) {
            if (fact.requested()) {
                counts.requested++;
            }
            switch (fact.applicability()) {
                case APPLICABLE -> counts.applicable++;
                case NOT_APPLICABLE -> counts.notApplicable++;
                case UNKNOWN -> counts.unknown++;
            }
            if (fact.requested()
                    && fact.applicability()
                            == ProtectionApplicability.APPLICABLE) {
                counts.requestedApplicable++;
            }
            if (fact.affected()) {
                counts.affected++;
            }
        }
        return counts;
    }

    private int rate(int numerator, int denominator) {
        return denominator == 0
                ? 0
                : (int) ((long) numerator * 10_000 / denominator);
    }

    private static final class Counts {
        private int requested;
        private int applicable;
        private int notApplicable;
        private int unknown;
        private int affected;
        private int requestedApplicable;
    }
}
