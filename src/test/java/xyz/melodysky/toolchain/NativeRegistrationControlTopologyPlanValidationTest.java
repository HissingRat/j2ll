package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.melodysky.packaging.NativeRegistrationEntry;
import xyz.melodysky.packaging.NativeRegistrationPlan;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;
import xyz.melodysky.toolchain.nativetext.NativeTextEncodingTestFixture;

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
                        second.owners()));
        assertThrows(
                IllegalArgumentException.class,
                () -> copy(plan, plan.owners(), backward));

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
                        plan.failureSymbols()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NativeRegistrationControlTopologyPlan(
                        plan.owners().get(0).symbol(),
                        plan.owners(),
                        plan.chunks(),
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
                        secondChunk.owners()));
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

    @Test
    void rejectsEarlierRegistrationMaterialCollidingWithFutureControlSymbols() {
        NativeTextBuildKey key = NativeRegistrationControlTestFixture.key(
                "future-registration-material-collision");
        List<NativeRegistrationTextPlan.Owner> owners =
                NativeRegistrationControlTestFixture.physicalOwners(11, key);
        NativeRegistrationControlTopologyPlan baseline =
                new NativeRegistrationControlTopologyPlanner().plan(
                        owners,
                        key);
        List<String> futureControlSymbols = List.of(
                baseline.owners().get(1).symbol(),
                baseline.chunks().get(baseline.chunks().size() - 1).symbol());

        for (String collision : futureControlSymbols) {
            for (RegistrationMaterial material
                    : RegistrationMaterial.values()) {
                ArrayList<NativeRegistrationTextPlan.Owner> mutated =
                        new ArrayList<>(owners);
                mutated.set(
                        0,
                        collide(mutated.get(0), material, collision));

                assertThrows(
                        IllegalArgumentException.class,
                        () -> new NativeRegistrationControlTopologyPlanner()
                                .plan(mutated, key),
                        material + " -> " + collision);
            }
        }
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
                plan.owners().subList(start, end));
    }

    private NativeRegistrationTextPlan.Owner collide(
            NativeRegistrationTextPlan.Owner owner,
            RegistrationMaterial material,
            String symbol) {
        NativeRegistrationTextPlan.Binding binding = owner.bindings().get(0);
        NativeRegistrationEntry registration = binding.registration();
        NativeRegistrationTextPlan.Binding collidedBinding = switch (material) {
            case NATIVE_SYMBOL -> new NativeRegistrationTextPlan.Binding(
                    new NativeRegistrationEntry(
                            registration.registrationOwner(),
                            registration.methodName(),
                            registration.descriptor(),
                            symbol),
                    binding.nameText(),
                    binding.descriptorText());
            case METHOD_TEXT -> new NativeRegistrationTextPlan.Binding(
                    registration,
                    NativeTextEncodingTestFixture.withSymbol(
                            binding.nameText(),
                            symbol),
                    binding.descriptorText());
            default -> binding;
        };
        List<NativeRegistrationTextPlan.Binding> bindings =
                material == RegistrationMaterial.NATIVE_SYMBOL
                                || material == RegistrationMaterial.METHOD_TEXT
                        ? List.of(collidedBinding)
                        : owner.bindings();
        var ownerText = material == RegistrationMaterial.OWNER_TEXT
                ? NativeTextEncodingTestFixture.withSymbol(
                        owner.ownerText(),
                        symbol)
                : owner.ownerText();
        List<NativeRegistrationTextPlan.TextGroup> groups = owner.textGroups();
        if (material == RegistrationMaterial.GROUP_ENCODING) {
            ArrayList<NativeRegistrationTextPlan.TextGroup> collidedGroups =
                    new ArrayList<>(groups);
            NativeRegistrationTextPlan.TextGroup group =
                    collidedGroups.get(0);
            collidedGroups.set(
                    0,
                    new NativeRegistrationTextPlan.TextGroup(
                            group.purpose(),
                            NativeTextEncodingTestFixture.withSymbol(
                                    group.encoding(),
                                    symbol),
                            group.memberOffsets()));
            groups = List.copyOf(collidedGroups);
        }
        return new NativeRegistrationTextPlan.Owner(
                owner.owner(),
                ownerText,
                bindings,
                groups);
    }

    private enum RegistrationMaterial {
        NATIVE_SYMBOL,
        OWNER_TEXT,
        METHOD_TEXT,
        GROUP_ENCODING
    }
}
