package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class NativeRegistrationControlRoutePlanValidationTest {
    @Test
    void rejectsMissingMalformedOrCollidingEntryRoutes() {
        NativeRegistrationControlTopologyPlan plan =
                NativeRegistrationControlTestFixture.plan(
                        11,
                        "registration-route-plan-validation");
        NativeRegistrationControlRoutePlan routePlan = plan.routePlan();

        assertThrows(
                IllegalArgumentException.class,
                () -> new NativeRegistrationControlTopologyPlan(
                        plan.aggregateSymbol(),
                        plan.owners(),
                        plan.chunks(),
                        NativeRegistrationControlRoutePlan.disabled(),
                        plan.failureSymbols()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NativeRegistrationControlRoutePlan(
                        routePlan.routes().subList(0, 2),
                        routePlan.rootGuardSalt(),
                        routePlan.rootSelectorSalt(),
                        routePlan.rootPostCallSalt(),
                        routePlan.rootSelectorShift()));

        ArrayList<NativeRegistrationControlRoutePlan.Route> wrongEdge =
                new ArrayList<>(routePlan.routes());
        NativeRegistrationControlRoutePlan.Route middle = wrongEdge.get(1);
        wrongEdge.set(
                1,
                route(
                        middle,
                        middle.symbol(),
                        middle.parameterOrder(),
                        NativeRegistrationControlRoutePlan.TargetKind.AGGREGATE,
                        -1,
                        middle.postCallRecipe(),
                        middle.witnessSalt(),
                        middle.postCallSalt()));
        assertThrows(
                IllegalArgumentException.class,
                () -> copy(routePlan, wrongEdge));

        NativeRegistrationControlRoutePlan.Route first = routePlan.route(0);
        NativeRegistrationControlRoutePlan.Route second = routePlan.route(1);
        assertThrows(
                IllegalArgumentException.class,
                () -> new NativeRegistrationControlRoutePlan.Route(
                        first.ordinal(),
                        "j2ll_registration_route",
                        first.parameterOrder(),
                        first.targetKind(),
                        first.targetRouteOrdinal(),
                        first.postCallRecipe(),
                        first.witnessSalt(),
                        first.postCallSalt()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NativeRegistrationControlRoutePlan.Route(
                        first.ordinal(),
                        first.symbol(),
                        List.of(
                                NativeRegistrationControlRoutePlan.Parameter.VM,
                                NativeRegistrationControlRoutePlan.Parameter.RESERVED,
                                NativeRegistrationControlRoutePlan.Parameter.RESERVED),
                        first.targetKind(),
                        first.targetRouteOrdinal(),
                        first.postCallRecipe(),
                        first.witnessSalt(),
                        first.postCallSalt()));

        ArrayList<NativeRegistrationControlRoutePlan.Route> duplicateOrder =
                new ArrayList<>(routePlan.routes());
        duplicateOrder.set(
                1,
                route(
                        second,
                        second.symbol(),
                        first.parameterOrder(),
                        second.targetKind(),
                        second.targetRouteOrdinal(),
                        second.postCallRecipe(),
                        second.witnessSalt(),
                        second.postCallSalt()));
        assertThrows(
                IllegalArgumentException.class,
                () -> copy(routePlan, duplicateOrder));

        ArrayList<NativeRegistrationControlRoutePlan.Route> duplicateMaterial =
                new ArrayList<>(routePlan.routes());
        duplicateMaterial.set(
                1,
                route(
                        second,
                        second.symbol(),
                        second.parameterOrder(),
                        second.targetKind(),
                        second.targetRouteOrdinal(),
                        second.postCallRecipe(),
                        first.witnessSalt(),
                        second.postCallSalt()));
        assertThrows(
                IllegalArgumentException.class,
                () -> copy(routePlan, duplicateMaterial));

        ArrayList<NativeRegistrationControlRoutePlan.Route> aggregateCollision =
                new ArrayList<>(routePlan.routes());
        aggregateCollision.set(
                0,
                route(
                        first,
                        plan.aggregateSymbol(),
                        first.parameterOrder(),
                        first.targetKind(),
                        first.targetRouteOrdinal(),
                        first.postCallRecipe(),
                        first.witnessSalt(),
                        first.postCallSalt()));
        NativeRegistrationControlRoutePlan collided =
                copy(routePlan, aggregateCollision);
        assertThrows(
                IllegalArgumentException.class,
                () -> new NativeRegistrationControlTopologyPlan(
                        plan.aggregateSymbol(),
                        plan.owners(),
                        plan.chunks(),
                        collided,
                        plan.failureSymbols()));
    }

    private NativeRegistrationControlRoutePlan copy(
            NativeRegistrationControlRoutePlan source,
            List<NativeRegistrationControlRoutePlan.Route> routes) {
        return new NativeRegistrationControlRoutePlan(
                routes,
                source.rootGuardSalt(),
                source.rootSelectorSalt(),
                source.rootPostCallSalt(),
                source.rootSelectorShift());
    }

    private NativeRegistrationControlRoutePlan.Route route(
            NativeRegistrationControlRoutePlan.Route source,
            String symbol,
            List<NativeRegistrationControlRoutePlan.Parameter> order,
            NativeRegistrationControlRoutePlan.TargetKind targetKind,
            int targetRouteOrdinal,
            NativeRegistrationPostCallRecipe recipe,
            long witnessSalt,
            long postCallSalt) {
        return new NativeRegistrationControlRoutePlan.Route(
                source.ordinal(),
                symbol,
                order,
                targetKind,
                targetRouteOrdinal,
                recipe,
                witnessSalt,
                postCallSalt);
    }
}
