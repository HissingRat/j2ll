package xyz.melodysky.ir.pass.protection;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable region selection and stable outcome for one IR method. */
public record ControlFlowFlatteningPlan(
        String methodKey,
        List<ControlFlowFlatteningRegion> regions,
        String reasonCode) {
    public static final int MAX_REGIONS = 4;

    public ControlFlowFlatteningPlan {
        Objects.requireNonNull(methodKey, "methodKey");
        Objects.requireNonNull(reasonCode, "reasonCode");
        if (methodKey.isBlank() || reasonCode.isBlank()) {
            throw new IllegalArgumentException("CFF plan identities must not be blank");
        }
        regions = List.copyOf(Objects.requireNonNull(regions, "regions"));
        if (regions.size() > MAX_REGIONS) {
            throw new IllegalArgumentException("CFF plan exceeds the per-method region limit");
        }
        HashSet<String> regionIds = new HashSet<>();
        HashSet<String> memberBlocks = new HashSet<>();
        for (ControlFlowFlatteningRegion region : regions) {
            if (!regionIds.add(region.regionId())) {
                throw new IllegalArgumentException("duplicate CFF region id " + region.regionId());
            }
            for (String member : region.memberBlocks()) {
                if (!memberBlocks.add(member)) {
                    throw new IllegalArgumentException("overlapping CFF region member " + member);
                }
            }
        }
    }

    public static ControlFlowFlatteningPlan selected(
            String methodKey,
            List<ControlFlowFlatteningRegion> regions) {
        if (regions.isEmpty()) {
            throw new IllegalArgumentException("selected CFF plan requires at least one region");
        }
        return new ControlFlowFlatteningPlan(
                methodKey,
                regions,
                ControlFlowFlatteningRegionPlanner.APPLIED_REASON);
    }

    public static ControlFlowFlatteningPlan skipped(String methodKey, String reasonCode) {
        return new ControlFlowFlatteningPlan(methodKey, List.of(), reasonCode);
    }

    public boolean selected() {
        return !regions.isEmpty();
    }

    public Optional<ControlFlowFlatteningRegion> regionForBlock(String blockName) {
        return regions.stream().filter(region -> region.contains(blockName)).findFirst();
    }
}
