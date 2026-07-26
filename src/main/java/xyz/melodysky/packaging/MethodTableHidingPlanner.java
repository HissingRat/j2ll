package xyz.melodysky.packaging;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import xyz.melodysky.ir.pass.protection.ProtectionRandom;

/**
 * Produces a deterministic split-table registration plan.
 *
 * <p>Token collisions are resolved by a deterministic counter suffix. The
 * metadata and function-pointer tables deliberately use independent orders so
 * their array indices do not reveal the binding relationship.</p>
 */
public final class MethodTableHidingPlanner {
    public MethodTableHidingPlan plan(
            NativeRegistrationPlan registrationPlan,
            boolean enabled,
            long seed) {
        if (!enabled || registrationPlan.entries().isEmpty()) {
            return MethodTableHidingPlan.disabled();
        }
        ProtectionRandom random = new ProtectionRandom(seed);
        String stablePlanInput = registrationPlan.entries().stream()
                .sorted()
                .map(this::stableIdentity)
                .reduce((left, right) -> left + "|" + right)
                .orElse("");
        String planId = "mth_" + random.token(
                "METHOD_TABLE_HIDING_PLAN",
                stablePlanInput,
                32);

        Map<String, List<NativeRegistrationEntry>> byOwner = new TreeMap<>();
        for (NativeRegistrationEntry entry : registrationPlan.entries()) {
            byOwner.computeIfAbsent(entry.registrationOwner(), ignored -> new ArrayList<>())
                    .add(entry);
        }
        ArrayList<MethodTableHidingOwnerPlan> owners = new ArrayList<>();
        for (Map.Entry<String, List<NativeRegistrationEntry>> owner : byOwner.entrySet()) {
            List<NativeRegistrationEntry> entries = owner.getValue().stream().sorted().toList();
            HashSet<Long> usedTokens = new HashSet<>();
            ArrayList<MethodTableHidingEntry> planned = new ArrayList<>();
            for (NativeRegistrationEntry entry : entries) {
                int collisionCounter = 0;
                long token;
                do {
                    String suffix = collisionCounter == 0 ? "" : ":" + collisionCounter;
                    token = unsignedLong(random.token(
                            "METHOD_TABLE_HIDING_TOKEN",
                            planId + ":" + stableIdentity(entry) + suffix,
                            16));
                    collisionCounter++;
                } while (!usedTokens.add(token));
                planned.add(new MethodTableHidingEntry(entry, token));
            }
            long mask = unsignedLong(random.token(
                    "METHOD_TABLE_HIDING_MASK",
                    planId + ":" + owner.getKey(),
                    16));
            List<MethodTableHidingEntry> metadataOrder = planned.stream()
                    .sorted(Comparator
                            .comparing((MethodTableHidingEntry entry) -> random.token(
                                    "METHOD_TABLE_HIDING_METADATA_ORDER",
                                    planId + ":" + stableIdentity(entry.registration()),
                                    32))
                            .thenComparing(MethodTableHidingEntry::registration))
                    .toList();
            List<MethodTableHidingEntry> functionOrder = planned.stream()
                    .sorted(Comparator
                            .comparing((MethodTableHidingEntry entry) -> random.token(
                                    "METHOD_TABLE_HIDING_FUNCTION_ORDER",
                                    planId + ":" + stableIdentity(entry.registration()),
                                    32))
                            .thenComparing(MethodTableHidingEntry::registration))
                    .toList();
            owners.add(new MethodTableHidingOwnerPlan(
                    owner.getKey(),
                    mask,
                    metadataOrder,
                    functionOrder));
        }
        return new MethodTableHidingPlan(true, planId, owners);
    }

    private long unsignedLong(String hex) {
        return Long.parseUnsignedLong(hex, 16);
    }

    private String stableIdentity(NativeRegistrationEntry entry) {
        return entry.registrationOwner() + "#" + entry.methodName() + "!"
                + entry.descriptor() + "->" + entry.nativeSymbol();
    }
}
