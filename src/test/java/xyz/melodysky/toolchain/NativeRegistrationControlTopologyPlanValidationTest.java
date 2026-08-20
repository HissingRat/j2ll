package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.melodysky.packaging.NativeRegistrationPlan;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;

final class NativeRegistrationControlTopologyPlanValidationTest {
    @Test
    void rejectsMissingReorderedAndNoncontiguousOwners() {
        NativeRegistrationControlTopologyPlan plan = fixture();

        assertThrows(
                IllegalArgumentException.class,
                () -> copy(plan, plan.owners().subList(0, 10), plan.chunks()));

        ArrayList<NativeRegistrationControlTopologyPlan.Owner> reordered =
                new ArrayList<>(plan.owners());
        java.util.Collections.swap(reordered, 0, 1);
        assertThrows(
                IllegalArgumentException.class,
                () -> copy(plan, reordered, plan.chunks()));

        ArrayList<NativeRegistrationControlTopologyPlan.Owner> noncontiguous =
                new ArrayList<>(plan.owners());
        NativeRegistrationControlTopologyPlan.Owner owner = noncontiguous.get(5);
        noncontiguous.set(
                5,
                new NativeRegistrationControlTopologyPlan.Owner(
                        7,
                        owner.source(),
                        owner.symbol()));
        assertThrows(
                IllegalArgumentException.class,
                () -> copy(plan, noncontiguous, plan.chunks()));
    }

    @Test
    void rejectsSkippedDuplicateBackwardAndUnbalancedChunks() {
        NativeRegistrationControlTopologyPlan plan = fixture();

        assertThrows(
                IllegalArgumentException.class,
                () -> copy(
                        plan,
                        plan.owners(),
                        plan.chunks().subList(0, 2)));

        ArrayList<NativeRegistrationControlTopologyPlan.Chunk> duplicated =
                new ArrayList<>(plan.chunks());
        duplicated.set(1, duplicated.get(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> copy(plan, plan.owners(), duplicated));

        ArrayList<NativeRegistrationControlTopologyPlan.Chunk> backward =
                new ArrayList<>(plan.chunks());
        NativeRegistrationControlTopologyPlan.Chunk second = backward.get(1);
        backward.set(
                1,
                new NativeRegistrationControlTopologyPlan.Chunk(
                        1,
                        second.startInclusive() - 1,
                        second.endExclusive(),
                        second.symbol(),
                        second.owners(),
                        second.postCallVariant(),
                        second.witnessSalt(),
                        second.postCallSalt()));
        assertThrows(
                IllegalArgumentException.class,
                () -> copy(plan, plan.owners(), backward));

        ArrayList<NativeRegistrationControlTopologyPlan.Chunk> duplicateVariant =
                new ArrayList<>(plan.chunks());
        NativeRegistrationControlTopologyPlan.Chunk first =
                duplicateVariant.get(0);
        NativeRegistrationControlTopologyPlan.Chunk duplicate =
                duplicateVariant.get(1);
        duplicateVariant.set(
                1,
                new NativeRegistrationControlTopologyPlan.Chunk(
                        duplicate.ordinal(),
                        duplicate.startInclusive(),
                        duplicate.endExclusive(),
                        duplicate.symbol(),
                        duplicate.owners(),
                        first.postCallVariant(),
                        duplicate.witnessSalt(),
                        duplicate.postCallSalt()));
        assertThrows(
                IllegalArgumentException.class,
                () -> copy(plan, plan.owners(), duplicateVariant));

        ArrayList<NativeRegistrationControlTopologyPlan.Chunk> zeroMaterial =
                new ArrayList<>(plan.chunks());
        NativeRegistrationControlTopologyPlan.Chunk zero =
                zeroMaterial.get(0);
        zeroMaterial.set(
                0,
                new NativeRegistrationControlTopologyPlan.Chunk(
                        zero.ordinal(),
                        zero.startInclusive(),
                        zero.endExclusive(),
                        zero.symbol(),
                        zero.owners(),
                        zero.postCallVariant(),
                        0L,
                        zero.postCallSalt()));
        assertThrows(
                IllegalArgumentException.class,
                () -> copy(plan, plan.owners(), zeroMaterial));

        NativeRegistrationControlTopologyPlan five =
                NativeRegistrationControlTestFixture.plan(5, "unbalanced-chunks");
        List<NativeRegistrationControlTopologyPlan.Chunk> unbalanced = List.of(
                chunk(five, 0, 0, 4),
                chunk(five, 1, 4, 5));
        assertThrows(
                IllegalArgumentException.class,
                () -> copy(five, five.owners(), unbalanced));
    }

    @Test
    void rejectsNonHashControlSymbolsAndEveryCollisionClass() {
        NativeRegistrationControlTopologyPlan plan = fixture();

        assertThrows(
                IllegalArgumentException.class,
                () -> new NativeRegistrationControlTopologyPlan(
                        "j2ll_registration_root",
                        plan.owners(),
                        plan.chunks(),
                        plan.routePlan(),
                        plan.failureSymbols()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NativeRegistrationControlTopologyPlan(
                        plan.owners().get(0).symbol(),
                        plan.owners(),
                        plan.chunks(),
                        plan.routePlan(),
                        plan.failureSymbols()));
        ArrayList<NativeRegistrationControlTopologyPlan.Owner> ownerCollision =
                new ArrayList<>(plan.owners());
        NativeRegistrationControlTopologyPlan.Owner secondOwner =
                ownerCollision.get(1);
        ownerCollision.set(
                1,
                new NativeRegistrationControlTopologyPlan.Owner(
                        secondOwner.index(),
                        secondOwner.source(),
                        ownerCollision.get(0).symbol()));
        assertThrows(
                IllegalArgumentException.class,
                () -> copy(plan, ownerCollision, plan.chunks()));
        ArrayList<NativeRegistrationControlTopologyPlan.Chunk> chunkCollision =
                new ArrayList<>(plan.chunks());
        NativeRegistrationControlTopologyPlan.Chunk secondChunk =
                chunkCollision.get(1);
        chunkCollision.set(
                1,
                new NativeRegistrationControlTopologyPlan.Chunk(
                        secondChunk.ordinal(),
                        secondChunk.startInclusive(),
                        secondChunk.endExclusive(),
                        chunkCollision.get(0).symbol(),
                        secondChunk.owners(),
                        secondChunk.postCallVariant(),
                        secondChunk.witnessSalt(),
                        secondChunk.postCallSalt()));
        assertThrows(
                IllegalArgumentException.class,
                () -> copy(plan, plan.owners(), chunkCollision));
        NativeRegistrationControlTopologyPlan.Owner firstOwner =
                plan.owners().get(0);
        assertThrows(
                IllegalArgumentException.class,
                () -> new NativeRegistrationControlTopologyPlan.Owner(
                        firstOwner.index(),
                        firstOwner.source(),
                        "registration_owner_control"));
        NativeRegistrationControlTopologyPlan.FailureSymbols duplicates =
                new NativeRegistrationControlTopologyPlan.FailureSymbols(
                        plan.failureSymbols().ownerRollback(),
                        plan.failureSymbols().ownerRollback(),
                        plan.failureSymbols().aggregateRollback(),
                        plan.failureSymbols().aggregateExceptionRestore());
        assertThrows(
                IllegalArgumentException.class,
                () -> new NativeRegistrationControlTopologyPlan(
                        plan.aggregateSymbol(),
                        plan.owners(),
                        plan.chunks(),
                        plan.routePlan(),
                        duplicates));

        NativeTextBuildKey key = NativeRegistrationControlTestFixture.key(
                "registration-material-collision");
        String aggregate =
                new NativeRegistrationControlSymbolDeriver(key)
                        .aggregateSymbol();
        NativeRegistrationPlan registrations =
                NativeRegistrationControlTestFixture.registrations(
                        1,
                        ignored -> aggregate);
        List<NativeRegistrationTextPlan.Owner> owners =
                NativeRegistrationTextPlan.ordinary(registrations, key);
        assertThrows(
                IllegalArgumentException.class,
                () -> new NativeRegistrationControlTopologyPlanner()
                                .plan(owners, key));
    }

    private NativeRegistrationControlTopologyPlan fixture() {
        return NativeRegistrationControlTestFixture.plan(
                11,
                "registration-control-validation");
    }

    private NativeRegistrationControlTopologyPlan copy(
            NativeRegistrationControlTopologyPlan source,
            List<NativeRegistrationControlTopologyPlan.Owner> owners,
            List<NativeRegistrationControlTopologyPlan.Chunk> chunks) {
        return new NativeRegistrationControlTopologyPlan(
                source.aggregateSymbol(),
                owners,
                chunks,
                source.routePlan(),
                source.failureSymbols());
    }

    private NativeRegistrationControlTopologyPlan.Chunk chunk(
            NativeRegistrationControlTopologyPlan plan,
            int ordinal,
            int start,
            int end) {
        NativeRegistrationControlTopologyPlan.Chunk original =
                plan.chunks().get(ordinal);
        return new NativeRegistrationControlTopologyPlan.Chunk(
                ordinal,
                start,
                end,
                original.symbol(),
                plan.owners().subList(start, end),
                original.postCallVariant(),
                original.witnessSalt(),
                original.postCallSalt());
    }

}
