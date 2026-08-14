package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;

final class HostJniLowSensitivityThrowLeafPoolLifecycleTest {
    @Test
    void freezeHappensExactlyOnceAndRejectsFurtherCollection() {
        HostJniLowSensitivityThrowLeafPool pool = pool("freeze-build");
        pool.rewrite(
                "first-fragment",
                HostJniLowSensitivityThrowShardFixture
                        .singleFragment(1)
                        .fragments()
                        .get(0)
                        .source());

        HostJniLowSensitivityThrowShardPlan plan = pool.freeze();

        assertSame(plan, pool.frozenPlan());
        assertThrows(IllegalStateException.class, pool::freeze);
        assertThrows(
                IllegalStateException.class,
                () -> pool.rewrite("late-fragment", "static void late(void) {}"));
    }

    @Test
    void frozenPlanCannotBeObservedBeforeFreeze() {
        HostJniLowSensitivityThrowLeafPool pool = pool("not-frozen-build");

        assertThrows(IllegalStateException.class, pool::frozenPlan);
    }

    @Test
    void duplicateFragmentScopeFailsBeforeSiteIdentityCanAlias() {
        HostJniLowSensitivityThrowLeafPool pool = pool("duplicate-scope-build");
        String source = HostJniLowSensitivityThrowShardFixture
                .singleFragment(1)
                .fragments()
                .get(0)
                .source();
        pool.rewrite("duplicate-scope", source);

        assertThrows(
                IllegalArgumentException.class,
                () -> pool.rewrite("duplicate-scope", source));
    }

    @Test
    void anEmptyCollectionFreezesWithoutPhysicalShards() {
        HostJniLowSensitivityThrowLeafPool pool = pool("empty-build");
        pool.rewrite("ordinary-fragment", "static void ordinary(void) {}\n");

        HostJniLowSensitivityThrowShardPlan plan = pool.freeze();

        assertTrue(plan.isEmpty());
        assertTrue(plan.shards().isEmpty());
    }

    private HostJniLowSensitivityThrowLeafPool pool(String buildKey) {
        return new HostJniLowSensitivityThrowLeafPool(
                NativeTextBuildKey.fromUtf8(buildKey));
    }
}
