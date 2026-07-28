package xyz.melodysky.protection.audit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/** Computes exact cross-build differences over explicit pass/subject facts. */
public final class ProtectionCoverageDiffAudit {
    public static final String COVERAGE_STABLE =
            "PROTECTION_COVERAGE_STABLE";
    public static final String COVERAGE_CHANGED =
            "PROTECTION_COVERAGE_CHANGED";

    public ProtectionCoverageDiffMetric compare(
            ProtectionCoverageSnapshot first,
            ProtectionCoverageSnapshot second) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        Map<String, ProtectionPassCoverageFact> left = facts(first);
        Map<String, ProtectionPassCoverageFact> right = facts(second);
        TreeSet<String> common = new TreeSet<>(left.keySet());
        common.retainAll(right.keySet());
        TreeSet<String> added = new TreeSet<>(right.keySet());
        added.removeAll(left.keySet());
        TreeSet<String> removed = new TreeSet<>(left.keySet());
        removed.removeAll(right.keySet());

        TreeSet<String> passKeys = new TreeSet<>();
        first.facts().forEach(fact -> passKeys.add(fact.passKey()));
        second.facts().forEach(fact -> passKeys.add(fact.passKey()));
        ArrayList<ProtectionCoverageDiffRow> rows = new ArrayList<>();
        for (String passKey : passKeys) {
            rows.add(row(passKey, first.facts(), second.facts()));
        }
        int requestedChanged = changed(
                common,
                left,
                right,
                Change.REQUESTED);
        int applicabilityChanged = changed(
                common,
                left,
                right,
                Change.APPLICABILITY);
        int affectedChanged = changed(
                common,
                left,
                right,
                Change.AFFECTED);
        int statusChanged = changed(
                common,
                left,
                right,
                Change.STATUS);
        int reasonChanged = changed(
                common,
                left,
                right,
                Change.REASON);
        boolean stable = added.isEmpty()
                && removed.isEmpty()
                && requestedChanged == 0
                && applicabilityChanged == 0
                && affectedChanged == 0
                && statusChanged == 0
                && reasonChanged == 0;
        return new ProtectionCoverageDiffMetric(
                first.evaluatedFacts(),
                second.evaluatedFacts(),
                common.size(),
                added.size(),
                removed.size(),
                requestedChanged,
                applicabilityChanged,
                affectedChanged,
                statusChanged,
                reasonChanged,
                first.affectedFacts(),
                second.affectedFacts(),
                second.affectedFacts() - first.affectedFacts(),
                rows,
                stable ? COVERAGE_STABLE : COVERAGE_CHANGED);
    }

    private ProtectionCoverageDiffRow row(
            String passKey,
            List<ProtectionPassCoverageFact> first,
            List<ProtectionPassCoverageFact> second) {
        List<ProtectionPassCoverageFact> left = first.stream()
                .filter(fact -> fact.passKey().equals(passKey))
                .toList();
        List<ProtectionPassCoverageFact> right = second.stream()
                .filter(fact -> fact.passKey().equals(passKey))
                .toList();
        Map<String, ProtectionPassCoverageFact> leftSubjects = subjects(left);
        Map<String, ProtectionPassCoverageFact> rightSubjects = subjects(right);
        TreeSet<String> common = new TreeSet<>(leftSubjects.keySet());
        common.retainAll(rightSubjects.keySet());
        int separator = passKey.indexOf('\0');
        String layer = passKey.substring(0, separator);
        String passName = passKey.substring(separator + 1);
        return new ProtectionCoverageDiffRow(
                layer,
                passName,
                affected(left),
                affected(right),
                affected(right) - affected(left),
                common.size(),
                difference(rightSubjects, leftSubjects),
                difference(leftSubjects, rightSubjects),
                changed(
                        common,
                        leftSubjects,
                        rightSubjects,
                        Change.REQUESTED),
                changed(
                        common,
                        leftSubjects,
                        rightSubjects,
                        Change.APPLICABILITY),
                changed(
                        common,
                        leftSubjects,
                        rightSubjects,
                        Change.AFFECTED),
                changed(common, leftSubjects, rightSubjects, Change.STATUS),
                changed(common, leftSubjects, rightSubjects, Change.REASON));
    }

    private int affected(List<ProtectionPassCoverageFact> facts) {
        return (int) facts.stream()
                .filter(ProtectionPassCoverageFact::affected)
                .count();
    }

    private int difference(
            Map<String, ProtectionPassCoverageFact> values,
            Map<String, ProtectionPassCoverageFact> subtract) {
        return (int) values.keySet().stream()
                .filter(key -> !subtract.containsKey(key))
                .count();
    }

    private int changed(
            Iterable<String> keys,
            Map<String, ProtectionPassCoverageFact> first,
            Map<String, ProtectionPassCoverageFact> second,
            Change change) {
        int count = 0;
        for (String key : keys) {
            if (change.changed(first.get(key), second.get(key))) {
                count++;
            }
        }
        return count;
    }

    private Map<String, ProtectionPassCoverageFact> facts(
            ProtectionCoverageSnapshot snapshot) {
        TreeMap<String, ProtectionPassCoverageFact> values = new TreeMap<>();
        snapshot.facts().forEach(fact -> values.put(fact.factKey(), fact));
        return Collections.unmodifiableMap(values);
    }

    private Map<String, ProtectionPassCoverageFact> subjects(
            List<ProtectionPassCoverageFact> facts) {
        TreeMap<String, ProtectionPassCoverageFact> values = new TreeMap<>();
        facts.forEach(fact ->
                values.put(fact.subjectIdentityHash(), fact));
        return Collections.unmodifiableMap(values);
    }

    private enum Change {
        REQUESTED {
            @Override
            boolean changed(
                    ProtectionPassCoverageFact first,
                    ProtectionPassCoverageFact second) {
                return first.requested() != second.requested();
            }
        },
        APPLICABILITY {
            @Override
            boolean changed(
                    ProtectionPassCoverageFact first,
                    ProtectionPassCoverageFact second) {
                return first.applicability() != second.applicability();
            }
        },
        AFFECTED {
            @Override
            boolean changed(
                    ProtectionPassCoverageFact first,
                    ProtectionPassCoverageFact second) {
                return first.affected() != second.affected();
            }
        },
        STATUS {
            @Override
            boolean changed(
                    ProtectionPassCoverageFact first,
                    ProtectionPassCoverageFact second) {
                return !first.status().equals(second.status());
            }
        },
        REASON {
            @Override
            boolean changed(
                    ProtectionPassCoverageFact first,
                    ProtectionPassCoverageFact second) {
                return !first.reasonCode().equals(second.reasonCode());
            }
        };

        abstract boolean changed(
                ProtectionPassCoverageFact first,
                ProtectionPassCoverageFact second);
    }
}
