package xyz.melodysky.toolchain.localref;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import xyz.melodysky.ir.model.IrValue;

/**
 * Verified ownership/lifetime artifact consumed by LLVM lowering.
 */
public record NativeLocalReferencePlan(
        String methodKey,
        Map<String, NativeLocalReferenceOwnership> ownershipByValue,
        Map<NativeLocalReferenceInstructionSite, NativeLocalReferenceReleaseSchedule>
                instructionReleases,
        Map<String, List<IrValue>> terminatorReleases,
        Map<NativeLocalReferenceNormalEdge, List<IrValue>> normalEdgeReleases) {
    public NativeLocalReferencePlan {
        Objects.requireNonNull(methodKey, "methodKey");
        if (methodKey.isBlank()) {
            throw new IllegalArgumentException("methodKey must not be blank");
        }
        ownershipByValue = stableOwnership(ownershipByValue);
        instructionReleases = stableInstructionReleases(
                instructionReleases);
        terminatorReleases = stableBlockReleases(terminatorReleases);
        normalEdgeReleases = stableEdgeReleases(normalEdgeReleases);
    }

    public Optional<NativeLocalReferenceOwnership> ownershipOf(
            IrValue value) {
        Objects.requireNonNull(value, "value");
        return Optional.ofNullable(ownershipByValue.get(value.name()));
    }

    public NativeLocalReferenceReleaseSchedule releasesAfter(
            String blockName,
            int instructionIndex) {
        return instructionReleases.getOrDefault(
                new NativeLocalReferenceInstructionSite(
                        blockName,
                        instructionIndex),
                new NativeLocalReferenceReleaseSchedule(
                        List.of(),
                        List.of()));
    }

    public List<IrValue> releasesBeforeTerminator(String blockName) {
        return terminatorReleases.getOrDefault(blockName, List.of());
    }

    public List<IrValue> releasesOn(NativeLocalReferenceNormalEdge edge) {
        return normalEdgeReleases.getOrDefault(edge, List.of());
    }

    public boolean emitsReleases() {
        return !instructionReleases.isEmpty()
                || !terminatorReleases.isEmpty()
                || !normalEdgeReleases.isEmpty();
    }

    private static Map<String, NativeLocalReferenceOwnership>
            stableOwnership(
                    Map<String, NativeLocalReferenceOwnership> input) {
        LinkedHashMap<String, NativeLocalReferenceOwnership> result =
                new LinkedHashMap<>();
        Objects.requireNonNull(input, "ownershipByValue")
                .entrySet()
                .stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> result.put(
                        Objects.requireNonNull(
                                entry.getKey(),
                                "ownership value"),
                        Objects.requireNonNull(
                                entry.getValue(),
                                "ownership")));
        return Collections.unmodifiableMap(result);
    }

    private static Map<
                    NativeLocalReferenceInstructionSite,
                    NativeLocalReferenceReleaseSchedule>
            stableInstructionReleases(
                    Map<
                                    NativeLocalReferenceInstructionSite,
                                    NativeLocalReferenceReleaseSchedule>
                            input) {
        LinkedHashMap<
                        NativeLocalReferenceInstructionSite,
                        NativeLocalReferenceReleaseSchedule>
                result = new LinkedHashMap<>();
        Objects.requireNonNull(input, "instructionReleases")
                .entrySet()
                .stream()
                .sorted(Map.Entry.comparingByKey())
                .filter(entry -> !entry.getValue().isEmpty())
                .forEach(entry -> result.put(
                        entry.getKey(),
                        entry.getValue()));
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, List<IrValue>> stableBlockReleases(
            Map<String, List<IrValue>> input) {
        LinkedHashMap<String, List<IrValue>> result = new LinkedHashMap<>();
        Objects.requireNonNull(input, "terminatorReleases")
                .entrySet()
                .stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    List<IrValue> values = stableValues(entry.getValue());
                    if (!values.isEmpty()) {
                        result.put(entry.getKey(), values);
                    }
                });
        return Collections.unmodifiableMap(result);
    }

    private static Map<NativeLocalReferenceNormalEdge, List<IrValue>>
            stableEdgeReleases(
                    Map<NativeLocalReferenceNormalEdge, List<IrValue>>
                            input) {
        LinkedHashMap<NativeLocalReferenceNormalEdge, List<IrValue>> result =
                new LinkedHashMap<>();
        Objects.requireNonNull(input, "normalEdgeReleases")
                .entrySet()
                .stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    List<IrValue> values = stableValues(entry.getValue());
                    if (!values.isEmpty()) {
                        result.put(entry.getKey(), values);
                    }
                });
        return Collections.unmodifiableMap(result);
    }

    private static List<IrValue> stableValues(List<IrValue> values) {
        return Objects.requireNonNull(values, "release values").stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
    }
}
