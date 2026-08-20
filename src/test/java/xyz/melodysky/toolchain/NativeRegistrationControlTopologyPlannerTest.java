package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;

final class NativeRegistrationControlTopologyPlannerTest {
    @Test
    void sameBuildIsStableAndDifferentBuildChangesOrderAndControlSymbols() {
        NativeTextBuildKey firstKey =
                NativeRegistrationControlTestFixture.key("registration-control-a");
        NativeRegistrationControlTopologyPlan first = plan(11, firstKey);
        NativeRegistrationControlTopologyPlan repeated = plan(11, firstKey);

        assertEquals(snapshot(first), snapshot(repeated));

        NativeRegistrationControlTopologyPlan changed = null;
        for (int index = 0; index < 256 && changed == null; index++) {
            NativeTextBuildKey candidateKey =
                    NativeRegistrationControlTestFixture.key(
                            "registration-control-b-" + index);
            NativeRegistrationControlTopologyPlan candidate =
                    plan(11, candidateKey);
            if (!NativeRegistrationControlTestFixture.logicalOwnerOrder(first)
                    .equals(NativeRegistrationControlTestFixture
                            .logicalOwnerOrder(candidate))) {
                changed = candidate;
            }
        }

        assertNotNull(changed, "fixture must find a build-diverse physical owner order");
        assertEquals(
                NativeRegistrationControlTestFixture.logicalOwners(first),
                NativeRegistrationControlTestFixture.logicalOwners(changed));
        assertNotEquals(snapshot(first), snapshot(changed));
        assertTrue(Collections.disjoint(
                NativeRegistrationControlTestFixture.controlSymbols(first),
                NativeRegistrationControlTestFixture.controlSymbols(changed)));
    }

    @Test
    void zeroOneElevenAndAboveThirtyTwoOwnersUseBoundedBalancedPartitions() {
        NativeRegistrationControlTopologyPlan empty =
                NativeRegistrationControlTestFixture.plan(0, "boundary-0");
        NativeRegistrationControlTopologyPlan one =
                NativeRegistrationControlTestFixture.plan(1, "boundary-1");
        NativeRegistrationControlTopologyPlan eleven =
                NativeRegistrationControlTestFixture.plan(11, "boundary-11");
        NativeRegistrationControlTopologyPlan thirtyThree =
                NativeRegistrationControlTestFixture.plan(33, "boundary-33");

        assertEquals(0, empty.chunks().size());
        assertFalse(empty.routePlan().enabled());
        assertTrue(empty.routePlan().routes().isEmpty());
        assertPartition(empty, 0);

        assertEquals(1, one.chunks().size());
        assertTrue(one.routePlan().enabled());
        assertEquals(
                NativeRegistrationControlRoutePlan.ROUTE_COUNT,
                one.routePlan().routes().size());
        assertEquals(List.of(1), chunkSizes(one));
        assertPartition(one, 1);

        assertEquals(3, eleven.chunks().size());
        assertEquals(List.of(3, 4, 4), sortedChunkSizes(eleven));
        assertTrue(chunkSizes(eleven).stream().allMatch(size ->
                size <= NativeRegistrationControlTopologyPlan
                        .TARGET_MAX_OWNERS_PER_CHUNK));
        assertPartition(eleven, 11);

        assertEquals(
                NativeRegistrationControlTopologyPlan.MAX_CHUNKS,
                thirtyThree.chunks().size());
        assertEquals(
                List.of(4, 4, 4, 4, 4, 4, 4, 5),
                sortedChunkSizes(thirtyThree));
        assertEquals(
                NativeRegistrationControlTopologyPlan.MAX_CHUNKS,
                new HashSet<>(thirtyThree.chunks().stream()
                        .map(NativeRegistrationControlTopologyPlan.Chunk::postCallVariant)
                        .toList()).size());
        assertTrue(thirtyThree.chunks().stream().allMatch(chunk ->
                chunk.witnessSalt() != 0L
                        && chunk.postCallSalt() != 0L));
        assertPartition(thirtyThree, 33);
    }

    @Test
    void everyControlIdentifierIsAnExactUniqueHashOnlySymbol() {
        NativeRegistrationControlTopologyPlan plan =
                NativeRegistrationControlTestFixture.plan(
                        11,
                        "registration-control-symbols");
        List<String> symbols =
                NativeRegistrationControlTestFixture.controlSymbols(plan);

        assertTrue(symbols.stream().allMatch(symbol ->
                symbol.matches("[a-p]{32}")));
        assertEquals(symbols.size(), new HashSet<>(symbols).size());
        assertFalse(symbols.stream().anyMatch(symbol ->
                symbol.contains("j2ll")
                        || symbol.contains("register")
                        || symbol.contains("owner")
                        || symbol.contains("chunk")
                        || symbol.contains("route")
                        || symbol.contains("failure")));
    }

    private NativeRegistrationControlTopologyPlan plan(
            int ownerCount,
            NativeTextBuildKey key) {
        return new NativeRegistrationControlTopologyPlanner().plan(
                NativeRegistrationControlTestFixture.physicalOwners(
                        ownerCount,
                        key),
                key);
    }

    private Snapshot snapshot(NativeRegistrationControlTopologyPlan plan) {
        return new Snapshot(
                plan.aggregateSymbol(),
                NativeRegistrationControlTestFixture.logicalOwnerOrder(plan),
                plan.owners().stream()
                        .map(NativeRegistrationControlTopologyPlan.Owner::symbol)
                        .toList(),
                plan.chunks().stream()
                        .map(chunk -> new ChunkSnapshot(
                                chunk.ordinal(),
                                chunk.startInclusive(),
                                chunk.endExclusive(),
                                chunk.symbol(),
                                chunk.owners().stream()
                                        .map(NativeRegistrationControlTopologyPlan.Owner::symbol)
                                        .toList(),
                                chunk.postCallVariant(),
                                chunk.witnessSalt(),
                                chunk.postCallSalt()))
                        .toList(),
                new RoutePlanSnapshot(
                        plan.routePlan().rootGuardSalt(),
                        plan.routePlan().rootSelectorSalt(),
                        plan.routePlan().rootPostCallSalt(),
                        plan.routePlan().rootSelectorShift(),
                        plan.routePlan().routes().stream()
                                .map(route -> new RouteSnapshot(
                                        route.ordinal(),
                                        route.symbol(),
                                        route.parameterOrder(),
                                        route.targetKind(),
                                        route.targetRouteOrdinal(),
                                        route.postCallRecipe(),
                                        route.witnessSalt(),
                                        route.postCallSalt()))
                                .toList()),
                plan.failureSymbols().symbols());
    }

    private void assertPartition(
            NativeRegistrationControlTopologyPlan plan,
            int ownerCount) {
        assertEquals(ownerCount, plan.owners().size());
        int expectedStart = 0;
        ArrayList<NativeRegistrationControlTopologyPlan.Owner> flattened =
                new ArrayList<>();
        for (int ordinal = 0; ordinal < plan.chunks().size(); ordinal++) {
            NativeRegistrationControlTopologyPlan.Chunk chunk =
                    plan.chunks().get(ordinal);
            assertEquals(ordinal, chunk.ordinal());
            assertEquals(expectedStart, chunk.startInclusive());
            assertEquals(
                    plan.owners().subList(
                            chunk.startInclusive(),
                            chunk.endExclusive()),
                    chunk.owners());
            flattened.addAll(chunk.owners());
            expectedStart = chunk.endExclusive();
        }
        assertEquals(ownerCount, expectedStart);
        assertEquals(plan.owners(), flattened);
        int minimum = chunkSizes(plan).stream()
                .mapToInt(Integer::intValue)
                .min()
                .orElse(0);
        int maximum = chunkSizes(plan).stream()
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
        assertTrue(maximum - minimum <= 1);
    }

    private List<Integer> chunkSizes(
            NativeRegistrationControlTopologyPlan plan) {
        return plan.chunks().stream()
                .map(chunk -> chunk.endExclusive() - chunk.startInclusive())
                .toList();
    }

    private List<Integer> sortedChunkSizes(
            NativeRegistrationControlTopologyPlan plan) {
        return chunkSizes(plan).stream().sorted().toList();
    }

    private record Snapshot(
            String aggregate,
            List<String> logicalOwnerOrder,
            List<String> ownerSymbols,
            List<ChunkSnapshot> chunks,
            RoutePlanSnapshot routes,
            List<String> failureSymbols) {}

    private record ChunkSnapshot(
            int ordinal,
            int start,
            int end,
            String symbol,
            List<String> ownerSymbols,
            NativeRegistrationChunkPostCallVariant postCallVariant,
            long witnessSalt,
            long postCallSalt) {}

    private record RoutePlanSnapshot(
            long rootGuardSalt,
            long rootSelectorSalt,
            long rootPostCallSalt,
            int rootSelectorShift,
            List<RouteSnapshot> routes) {}

    private record RouteSnapshot(
            int ordinal,
            String symbol,
            List<NativeRegistrationControlRoutePlan.Parameter> parameterOrder,
            NativeRegistrationControlRoutePlan.TargetKind targetKind,
            int targetRouteOrdinal,
            NativeRegistrationPostCallRecipe postCallRecipe,
            long witnessSalt,
            long postCallSalt) {}
}
