package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

final class NativeRegistrationControlRoutePlannerTest {
    @Test
    void nonemptyPlanFreezesExactTwoPathThreeRouteGraphAndDiverseMaterial() {
        NativeRegistrationControlTopologyPlan plan =
                NativeRegistrationControlTestFixture.plan(
                        11,
                        "registration-control-routes");
        NativeRegistrationControlRoutePlan routes = plan.routePlan();

        assertTrue(routes.enabled());
        assertEquals(3, routes.routes().size());
        assertEquals(List.of(0, 1, 2), routes.routes().stream()
                .map(NativeRegistrationControlRoutePlan.Route::ordinal)
                .toList());
        assertEquals(
                NativeRegistrationControlRoutePlan.TargetKind.AGGREGATE,
                routes.route(0).targetKind());
        assertEquals(-1, routes.route(0).targetRouteOrdinal());
        assertEquals(
                NativeRegistrationControlRoutePlan.TargetKind.ROUTE,
                routes.route(1).targetKind());
        assertEquals(2, routes.route(1).targetRouteOrdinal());
        assertEquals(
                NativeRegistrationControlRoutePlan.TargetKind.AGGREGATE,
                routes.route(2).targetKind());
        assertEquals(-1, routes.route(2).targetRouteOrdinal());

        assertEquals(
                3,
                new HashSet<>(routes.routes().stream()
                        .map(NativeRegistrationControlRoutePlan.Route::parameterOrder)
                        .toList()).size());
        assertEquals(
                3,
                new HashSet<>(routes.routes().stream()
                        .map(NativeRegistrationControlRoutePlan.Route::postCallRecipe)
                        .toList()).size());
        assertTrue(routes.routes().stream().allMatch(route ->
                route.parameterOrder().size()
                                == NativeRegistrationControlRoutePlan.Parameter
                                        .values().length
                        && new HashSet<>(route.parameterOrder()).size()
                                == NativeRegistrationControlRoutePlan.Parameter
                                        .values().length));
        HashSet<Long> material = new HashSet<>(List.of(
                routes.rootGuardSalt(),
                routes.rootSelectorSalt(),
                routes.rootPostCallSalt()));
        routes.routes().forEach(route -> {
            material.add(route.witnessSalt());
            material.add(route.postCallSalt());
        });
        assertEquals(9, material.size());
        assertFalse(material.contains(0L));
        assertTrue(routes.rootSelectorShift() >= 1);
        assertTrue(routes.rootSelectorShift() <= 31);
    }
}
