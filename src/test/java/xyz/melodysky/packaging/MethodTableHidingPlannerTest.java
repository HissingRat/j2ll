package xyz.melodysky.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class MethodTableHidingPlannerTest {
    private final NativeRegistrationPlan registrations = new NativeRegistrationPlan(List.of(
            new NativeRegistrationEntry("sample/Owner", "first", "(I)I", "j2ll_fn_a"),
            new NativeRegistrationEntry("sample/Owner", "second", "(J)J", "j2ll_fn_b"),
            new NativeRegistrationEntry("sample/Other", "third", "()V", "j2ll_fn_c")));

    @Test
    void sameSeedProducesStableCollisionFreeTransientOwnerLayouts() {
        MethodTableHidingPlanner planner = new MethodTableHidingPlanner();

        MethodTableHidingPlan first = planner.plan(registrations, true, 41L);
        MethodTableHidingPlan second = planner.plan(registrations, true, 41L);

        assertEquals(first, second);
        assertTrue(first.changed());
        for (MethodTableHidingOwnerPlan owner : first.owners()) {
            assertEquals(
                    owner.registrationOrder().size(),
                    owner.registrationOrder().stream()
                            .map(MethodTableHidingEntry::token)
                            .distinct()
                            .count());
            for (MethodTableHidingEntry entry : owner.registrationOrder()) {
                assertEquals(entry.registration(), owner.require(entry.token()));
            }
        }
    }

    @Test
    void seedChangesOpaqueIdentityWithoutChangingBindings() {
        MethodTableHidingPlan first = new MethodTableHidingPlanner().plan(registrations, true, 11L);
        MethodTableHidingPlan second = new MethodTableHidingPlanner().plan(registrations, true, 12L);

        assertNotEquals(first.planId(), second.planId());
        assertEquals(
                registrations.entries(),
                first.owners().stream()
                        .flatMap(owner -> owner.registrationOrder().stream())
                        .map(MethodTableHidingEntry::registration)
                        .sorted()
                        .toList());
    }

    @Test
    void unknownTokenFailsClosed() {
        MethodTableHidingOwnerPlan owner = new MethodTableHidingPlanner()
                .plan(registrations, true, 2L)
                .owner("sample/Owner")
                .orElseThrow();
        long wrong = owner.registrationOrder().get(0).token() ^ Long.MIN_VALUE;

        assertThrows(IllegalArgumentException.class, () -> owner.require(wrong));
    }

    @Test
    void disabledModeIsAnEmptyNoOpPlan() {
        assertEquals(
                MethodTableHidingPlan.disabled(),
                new MethodTableHidingPlanner().plan(registrations, false, 99L));
    }
}
