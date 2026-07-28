package xyz.melodysky.protection.audit;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/** Aggregates explicit wrapper evidence and compares mapping reuse across builds. */
public final class WrapperCallShapeAudit {
    public static final String FINAL_BINARY_REUSE_MEASURED =
            "WRAPPER_FINAL_BINARY_MAPPING_REUSE_MEASURED";
    public static final String STRUCTURAL_REUSE_MEASURED =
            "WRAPPER_STRUCTURAL_MAPPING_REUSE_MEASURED";
    public static final String NO_COMMON_BINDINGS =
            "WRAPPER_MAPPING_REUSE_NO_COMMON_BINDINGS";

    public WrapperCallShapeMetric summarize(
            List<WrapperCallEvidence> evidence) {
        Map<String, WrapperCallEvidence> byBinding = byBinding(evidence);
        EnumMap<WrapperCallShape, Integer> shapeCounts =
                new EnumMap<>(WrapperCallShape.class);
        TreeMap<String, Integer> kindCounts = new TreeMap<>();
        boolean finalBinary = true;
        for (WrapperCallEvidence wrapper : byBinding.values()) {
            shapeCounts.merge(wrapper.shape(), 1, Integer::sum);
            kindCounts.merge(
                    wrapper.evidenceKind().wireName(),
                    1,
                    Integer::sum);
            finalBinary &= wrapper.evidenceKind().finalBinaryEvidence();
        }
        int unresolved = shapeCounts.getOrDefault(
                WrapperCallShape.UNRESOLVED,
                0);
        int indirect = shapeCounts.getOrDefault(
                        WrapperCallShape.INDIRECT_SLOT,
                        0)
                + shapeCounts.getOrDefault(
                        WrapperCallShape.INDIRECT_DISPATCH,
                        0);
        return new WrapperCallShapeMetric(
                byBinding.size(),
                byBinding.size() - unresolved,
                shapeCounts.getOrDefault(
                        WrapperCallShape.DIRECT_SINGLE_CALLEE,
                        0),
                indirect,
                shapeCounts.getOrDefault(
                        WrapperCallShape.MULTIPLE_CALLEES,
                        0),
                unresolved,
                !byBinding.isEmpty() && finalBinary,
                kindCounts,
                List.copyOf(byBinding.values()));
    }

    public WrapperMappingReuseMetric compare(
            WrapperCallShapeMetric first,
            WrapperCallShapeMetric second) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        Map<String, WrapperCallEvidence> firstByBinding =
                byBinding(first.wrappers());
        Map<String, WrapperCallEvidence> secondByBinding =
                byBinding(second.wrappers());
        TreeSet<String> common = new TreeSet<>(firstByBinding.keySet());
        common.retainAll(secondByBinding.keySet());
        TreeSet<String> added = new TreeSet<>(secondByBinding.keySet());
        added.removeAll(firstByBinding.keySet());
        TreeSet<String> removed = new TreeSet<>(firstByBinding.keySet());
        removed.removeAll(secondByBinding.keySet());

        ArrayList<String> reusable = new ArrayList<>();
        ArrayList<String> shapeChanged = new ArrayList<>();
        ArrayList<String> resolutionChanged = new ArrayList<>();
        ArrayList<String> unresolved = new ArrayList<>();
        for (String binding : common) {
            WrapperCallEvidence left = firstByBinding.get(binding);
            WrapperCallEvidence right = secondByBinding.get(binding);
            if (left.shape() == WrapperCallShape.UNRESOLVED
                    || right.shape() == WrapperCallShape.UNRESOLVED) {
                unresolved.add(binding);
            } else if (left.shape() != right.shape()) {
                shapeChanged.add(binding);
            } else if (!left.resolutionFingerprintHash()
                    .equals(right.resolutionFingerprintHash())) {
                resolutionChanged.add(binding);
            } else {
                reusable.add(binding);
            }
        }
        int basisPoints = common.isEmpty()
                ? 0
                : (int) ((long) reusable.size() * 10_000 / common.size());
        boolean finalBinary =
                first.finalBinaryEvidence() && second.finalBinaryEvidence();
        String reason = common.isEmpty()
                ? NO_COMMON_BINDINGS
                : finalBinary
                        ? FINAL_BINARY_REUSE_MEASURED
                        : STRUCTURAL_REUSE_MEASURED;
        return new WrapperMappingReuseMetric(
                first.wrapperCount(),
                second.wrapperCount(),
                common.size(),
                reusable.size(),
                basisPoints,
                shapeChanged.size(),
                resolutionChanged.size(),
                unresolved.size(),
                finalBinary,
                reusable,
                shapeChanged,
                resolutionChanged,
                unresolved,
                List.copyOf(added),
                List.copyOf(removed),
                reason);
    }

    private Map<String, WrapperCallEvidence> byBinding(
            List<WrapperCallEvidence> evidence) {
        Objects.requireNonNull(evidence, "evidence");
        TreeMap<String, WrapperCallEvidence> sorted = new TreeMap<>();
        for (WrapperCallEvidence wrapper : evidence) {
            Objects.requireNonNull(wrapper, "wrapper evidence");
            WrapperCallEvidence duplicate =
                    sorted.putIfAbsent(wrapper.bindingIdentityHash(), wrapper);
            if (duplicate != null) {
                throw new IllegalArgumentException(
                        "duplicate wrapper binding evidence: "
                                + wrapper.bindingIdentityHash());
            }
        }
        return java.util.Collections.unmodifiableMap(sorted);
    }
}
