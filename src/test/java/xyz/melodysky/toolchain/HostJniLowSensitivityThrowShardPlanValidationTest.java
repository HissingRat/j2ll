package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class HostJniLowSensitivityThrowShardPlanValidationTest {
    private static final String ANCHOR =
            "j2ll_low_throw_declarations_"
                    + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String LEAF_IDENTITY =
            HostJniLowSensitivityThrowShardFixture.EXCEPTION_CLASS
                    + '\0'
                    + HostJniLowSensitivityThrowShardFixture.MESSAGE;

    @Test
    void rejectsUnassignedAndMultiplyAssignedSites() {
        HostJniLowSensitivityThrowShardPlan.Site first = site(0);
        HostJniLowSensitivityThrowShardPlan.Site second = site(1);

        assertThrows(
                IllegalArgumentException.class,
                () -> new HostJniLowSensitivityThrowShardPlan(
                        ANCHOR,
                        List.of(first, second),
                        List.of(shard(
                                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                                0,
                                List.of(first)))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new HostJniLowSensitivityThrowShardPlan(
                        ANCHOR,
                        List.of(first),
                        List.of(
                                shard(
                                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                                        0,
                                        List.of(first)),
                                 shard(
                                         "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                                         1,
                                         List.of(first)))));
    }

    @Test
    void rejectsDuplicateSiteIdentityAndPlaceholder() {
        HostJniLowSensitivityThrowShardPlan.Site first = site(0);
        HostJniLowSensitivityThrowShardPlan.Site duplicate =
                new HostJniLowSensitivityThrowShardPlan.Site(
                        first.placeholder(),
                        first.identity(),
                        first.scope(),
                        first.leafLocalOrdinal(),
                        first.leafIdentity(),
                        first.exceptionClass(),
                        first.message());

        assertThrows(
                IllegalArgumentException.class,
                () -> new HostJniLowSensitivityThrowShardPlan(
                        ANCHOR,
                        List.of(first, duplicate),
                        List.of()));
    }

    @Test
    void rejectsPhysicalFanoutAboveThirtyTwo() {
        ArrayList<HostJniLowSensitivityThrowShardPlan.Site> sites =
                new ArrayList<>();
        for (int index = 0;
                index
                        < HostJniLowSensitivityThrowShardPlan
                                .MAX_DIRECT_CALL_SITES_PER_SHARD + 1;
                index++) {
            sites.add(site(index));
        }

        assertThrows(
                IllegalArgumentException.class,
                () -> new HostJniLowSensitivityThrowShardPlan(
                        ANCHOR,
                        sites,
                        List.of(shard(
                                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                                0,
                                sites))));
    }

    @Test
    void rejectsMixedLogicalLeafInsideOnePhysicalShard() {
        HostJniLowSensitivityThrowShardPlan.Site first = site(0);
        String otherClass = "java/lang/IllegalArgumentException";
        String otherMessage = "negative";
        HostJniLowSensitivityThrowShardPlan.Site other =
                new HostJniLowSensitivityThrowShardPlan.Site(
                        "j2ll_low_throw_site_"
                                + "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                        "validation-scope\0"
                                + otherClass
                                + '\0'
                                + otherMessage
                                + '\0'
                                + 1,
                        "validation-scope",
                        1,
                        otherClass + '\0' + otherMessage,
                        otherClass,
                        otherMessage);

        assertThrows(
                IllegalArgumentException.class,
                () -> new HostJniLowSensitivityThrowShardPlan(
                        ANCHOR,
                        List.of(first, other),
                        List.of(shard(
                                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                                0,
                                List.of(first, other)))));
    }

    private HostJniLowSensitivityThrowShardPlan.Site site(int index) {
        String scope = "validation-scope";
        return new HostJniLowSensitivityThrowShardPlan.Site(
                "j2ll_low_throw_site_" + hashToken(index),
                scope + '\0' + LEAF_IDENTITY + '\0' + index,
                scope,
                index,
                LEAF_IDENTITY,
                HostJniLowSensitivityThrowShardFixture.EXCEPTION_CLASS,
                HostJniLowSensitivityThrowShardFixture.MESSAGE);
    }

    private HostJniLowSensitivityThrowShardPlan.Shard shard(
            String symbol,
            int ordinal,
            List<HostJniLowSensitivityThrowShardPlan.Site> sites) {
        return new HostJniLowSensitivityThrowShardPlan.Shard(
                symbol,
                ordinal,
                LEAF_IDENTITY,
                HostJniLowSensitivityThrowShardFixture.EXCEPTION_CLASS,
                HostJniLowSensitivityThrowShardFixture.MESSAGE,
                sites);
    }

    private String hashToken(int value) {
        char high = (char) ('a' + ((value >>> 4) & 0x0f));
        char low = (char) ('a' + (value & 0x0f));
        return ("a".repeat(30)) + high + low;
    }
}
