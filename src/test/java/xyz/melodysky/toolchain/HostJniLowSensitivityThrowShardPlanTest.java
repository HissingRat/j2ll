package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;

final class HostJniLowSensitivityThrowShardPlanTest {
    @Test
    void boundaryCountsUseBalancedShardsOfAtMostThirtyTwoSites() {
        for (int useCount : List.of(0, 1, 32, 33, 64, 65, 128)) {
            HostJniLowSensitivityThrowShardPlan plan = plan(
                    "boundary-build-" + useCount,
                    HostJniLowSensitivityThrowShardFixture
                            .singleFragment(useCount));

            assertEquals(useCount, plan.siteCount(), "useCount=" + useCount);
            assertEquals(
                    HostJniLowSensitivityThrowShardFixture
                            .expectedShardCount(useCount),
                    plan.shards().size(),
                    "useCount=" + useCount);
            assertEquals(
                    useCount,
                    plan.shards().stream()
                            .mapToInt(shard -> shard.sites().size())
                            .sum(),
                    "useCount=" + useCount);
            assertTrue(
                    plan.shards().stream().allMatch(shard ->
                            !shard.sites().isEmpty()
                                    && shard.sites().size()
                                            <= HostJniLowSensitivityThrowShardFixture
                                                    .MAX_USES_PER_SHARD),
                    "useCount=" + useCount);
            if (!plan.shards().isEmpty()) {
                int smallest = plan.shards().stream()
                        .mapToInt(shard -> shard.sites().size())
                        .min()
                        .orElseThrow();
                int largest = plan.shards().stream()
                        .mapToInt(shard -> shard.sites().size())
                        .max()
                        .orElseThrow();
                assertTrue(
                        largest - smallest <= 1,
                        "shards must be balanced for useCount=" + useCount);
            }
        }
    }

    @Test
    void sitesAccumulateAcrossFragmentsBeforeOneGlobalFreeze() {
        HostJniLowSensitivityThrowShardFixture.Scenario scenario =
                HostJniLowSensitivityThrowShardFixture.fragments(32, 32, 1);
        HostJniLowSensitivityThrowShardPlan plan = plan(
                "cross-fragment-build",
                scenario);

        assertEquals(65, plan.siteCount());
        assertEquals(3, plan.shards().size());
        assertEquals(
                Set.of(
                        "fixture-fragment-0",
                        "fixture-fragment-1",
                        "fixture-fragment-2"),
                plan.shards().stream()
                        .flatMap(shard -> shard.sites().stream())
                        .map(HostJniLowSensitivityThrowShardPlan.Site::scope)
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(
                65,
                plan.shards().stream()
                        .flatMap(shard -> shard.sites().stream())
                        .map(HostJniLowSensitivityThrowShardPlan.Site::identity)
                        .distinct()
                        .count());

        HostJniLowSensitivityThrowShardPlan splitBoundary = plan(
                "cross-fragment-boundary-build",
                HostJniLowSensitivityThrowShardFixture.fragments(16, 17));
        assertEquals(33, splitBoundary.siteCount());
        assertEquals(2, splitBoundary.shards().size());
    }

    @Test
    void sameBuildIsStableAndDifferentBuildChangesSymbolsAndMembership() {
        HostJniLowSensitivityThrowShardFixture.Scenario scenario =
                HostJniLowSensitivityThrowShardFixture.fragments(41, 24);
        HostJniLowSensitivityThrowShardPlan first = plan("build-a", scenario);
        HostJniLowSensitivityThrowShardPlan repeated = plan("build-a", scenario);
        HostJniLowSensitivityThrowShardPlan other = plan("build-b", scenario);

        assertEquals(snapshot(first), snapshot(repeated));
        assertNotEquals(symbols(first), symbols(other));
        assertNotEquals(partition(first), partition(other));
    }

    @Test
    void stableSiteIdentityMakesFragmentCollectionOrderIrrelevant() {
        HostJniLowSensitivityThrowShardFixture.Scenario scenario =
                HostJniLowSensitivityThrowShardFixture.fragments(18, 31, 16);
        HostJniLowSensitivityThrowLeafPool forward = pool("order-build");
        for (HostJniLowSensitivityThrowShardFixture.Fragment fragment
                : scenario.fragments()) {
            forward.rewrite(fragment.scope(), fragment.source());
        }

        HostJniLowSensitivityThrowLeafPool reverse = pool("order-build");
        ArrayList<HostJniLowSensitivityThrowShardFixture.Fragment> fragments =
                new ArrayList<>(scenario.fragments());
        java.util.Collections.reverse(fragments);
        for (HostJniLowSensitivityThrowShardFixture.Fragment fragment
                : fragments) {
            reverse.rewrite(fragment.scope(), fragment.source());
        }

        assertEquals(snapshot(forward.freeze()), snapshot(reverse.freeze()));
    }

    @Test
    void physicalSymbolsArePureHashOnlyJavaIdentifiers() {
        HostJniLowSensitivityThrowShardPlan plan = plan(
                "hash-only-build",
                HostJniLowSensitivityThrowShardFixture
                        .singleFragment(65));

        for (HostJniLowSensitivityThrowShardPlan.Shard shard
                : plan.shards()) {
            assertTrue(shard.symbol().matches("[a-p]{32}"), shard.symbol());
            assertFalse(shard.symbol().contains("j2ll"), shard.symbol());
            assertFalse(shard.symbol().contains("_"), shard.symbol());
            assertFalse(shard.symbol().contains("throw"), shard.symbol());
        }
    }

    private HostJniLowSensitivityThrowShardPlan plan(
            String buildKey,
            HostJniLowSensitivityThrowShardFixture.Scenario scenario) {
        HostJniLowSensitivityThrowLeafPool pool =
                pool(buildKey);
        for (HostJniLowSensitivityThrowShardFixture.Fragment fragment
                : scenario.fragments()) {
            pool.rewrite(fragment.scope(), fragment.source());
        }
        return pool.freeze();
    }

    private HostJniLowSensitivityThrowLeafPool pool(String buildKey) {
        return new HostJniLowSensitivityThrowLeafPool(
                NativeTextBuildKey.fromUtf8(buildKey));
    }

    private List<String> snapshot(
            HostJniLowSensitivityThrowShardPlan plan) {
        ArrayList<String> snapshot = new ArrayList<>();
        for (HostJniLowSensitivityThrowShardPlan.Shard shard
                : plan.shards()) {
            snapshot.add(shard.symbol() + ':' + shard.sites().stream()
                    .map(HostJniLowSensitivityThrowShardPlan.Site::identity)
                    .sorted()
                    .toList());
        }
        return List.copyOf(snapshot);
    }

    private Set<String> symbols(
            HostJniLowSensitivityThrowShardPlan plan) {
        return plan.shards().stream()
                .map(HostJniLowSensitivityThrowShardPlan.Shard::symbol)
                .collect(java.util.stream.Collectors.toSet());
    }

    private Set<Set<String>> partition(
            HostJniLowSensitivityThrowShardPlan plan) {
        HashSet<Set<String>> partition = new HashSet<>();
        for (HostJniLowSensitivityThrowShardPlan.Shard shard
                : plan.shards()) {
            partition.add(shard.sites().stream()
                    .map(HostJniLowSensitivityThrowShardPlan.Site::identity)
                    .collect(java.util.stream.Collectors.toSet()));
        }
        return Set.copyOf(partition);
    }
}
