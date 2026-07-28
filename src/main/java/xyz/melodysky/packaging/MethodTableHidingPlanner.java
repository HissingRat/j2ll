package xyz.melodysky.packaging;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import xyz.melodysky.ir.pass.protection.ProtectionRandom;

/**
 * Produces a deterministic, build-diverse owner-local registration layout.
 *
 * <p>Opaque tokens are retained only as hash-only report evidence. Generated
 * native code consumes the physical registration order directly and never
 * emits a persistent token or function-pointer database.</p>
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
            List<MethodTableHidingEntry> registrationOrder = planned.stream()
                    .sorted(Comparator
                            .comparing((MethodTableHidingEntry entry) -> random.token(
                                    "METHOD_TABLE_HIDING_REGISTRATION_ORDER",
                                    planId + ":" + stableIdentity(entry.registration()),
                                    32))
                            .thenComparing(MethodTableHidingEntry::registration))
                    .toList();
            owners.add(new MethodTableHidingOwnerPlan(
                    owner.getKey(),
                    registrationOrder));
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
