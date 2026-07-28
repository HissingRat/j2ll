package xyz.melodysky.protection.audit;

import java.util.Objects;

/** Per-pass cross-build protection coverage difference. */
public record ProtectionCoverageDiffRow(
        String layer,
        String passName,
        int firstAffectedSubjects,
        int secondAffectedSubjects,
        int affectedDelta,
        int commonSubjects,
        int addedSubjects,
        int removedSubjects,
        int requestedChangedSubjects,
        int applicabilityChangedSubjects,
        int affectedChangedSubjects,
        int statusChangedSubjects,
        int reasonChangedSubjects)
        implements Comparable<ProtectionCoverageDiffRow> {
    public ProtectionCoverageDiffRow {
        Objects.requireNonNull(layer, "layer");
        Objects.requireNonNull(passName, "passName");
        if (layer.isBlank()
                || passName.isBlank()
                || firstAffectedSubjects < 0
                || secondAffectedSubjects < 0
                || commonSubjects < 0
                || addedSubjects < 0
                || removedSubjects < 0
                || requestedChangedSubjects < 0
                || applicabilityChangedSubjects < 0
                || affectedChangedSubjects < 0
                || statusChangedSubjects < 0
                || reasonChangedSubjects < 0
                || affectedDelta
                        != secondAffectedSubjects - firstAffectedSubjects) {
            throw new IllegalArgumentException(
                    "protection coverage diff row is invalid");
        }
    }

    public String passKey() {
        return layer + "\0" + passName;
    }

    @Override
    public int compareTo(ProtectionCoverageDiffRow other) {
        int byLayer = layer.compareTo(other.layer);
        return byLayer != 0
                ? byLayer
                : passName.compareTo(other.passName);
    }
}
