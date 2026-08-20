package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

final class NativeRegistrationControlEmissionTest {
    @Test
    void finalVerifierAcceptsZeroOneElevenAndFallbackTopologies() {
        for (int ownerCount : List.of(0, 1, 11, 33)) {
            HostNativeRegistrationSource.Emission emission =
                    NativeRegistrationControlTestFixture.emission(
                            ownerCount,
                            "registration-emission-" + ownerCount);

            new NativeRegistrationControlSourceVerifier().verify(
                    emission.source(),
                    emission.topologyPlan());
        }
    }

    @Test
    void onLoadSelectsOnlyRouteZeroOrOneAndBothPathsReachOneAggregate() {
        HostNativeRegistrationSource.Emission emission =
                NativeRegistrationControlTestFixture.emission(
                        11,
                        "registration-root-closure");
        NativeRegistrationControlTopologyPlan plan =
                emission.topologyPlan();
        String source = emission.source();
        String onLoad = NativeRegistrationControlTestFixture.functionAtHeader(
                source,
                "JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved)");
        String aggregate = NativeRegistrationControlTestFixture.function(
                source,
                plan.aggregateSymbol());
        NativeRegistrationControlRoutePlan routes = plan.routePlan();

        assertEquals(
                2,
                NativeRegistrationControlTestFixture.occurrences(
                        source,
                        "JNIEXPORT jint JNICALL JNI_OnLoad"));
        assertTrue(onLoad.contains("if ("));
        assertEquals(
                1,
                NativeRegistrationControlTestFixture.occurrences(
                        onLoad,
                        HostNativeRegistrationRouteSource.routeCall(
                                routes.route(0))));
        assertEquals(
                1,
                NativeRegistrationControlTestFixture.occurrences(
                        onLoad,
                        HostNativeRegistrationRouteSource.routeCall(
                                routes.route(1))));
        assertFalse(onLoad.contains(routes.route(2).symbol() + "("));
        assertFalse(onLoad.contains(plan.aggregateSymbol() + "("));
        assertTrue(onLoad.contains("volatile uintptr_t guard"));
        assertTrue(onLoad.contains("volatile uintptr_t witness"));
        assertTrue(onLoad.contains("volatile jint result"));

        assertRouteEdge(source, plan, 0, plan.aggregateSymbol());
        assertRouteEdge(source, plan, 1, routes.route(2).symbol());
        assertRouteEdge(source, plan, 2, plan.aggregateSymbol());
        assertTrue(aggregate.contains(
                plan.chunks().get(0).symbol()
                        + "(env, &resolver, registered_owners, &registered_count)"));
        for (NativeRegistrationControlTopologyPlan.Owner owner
                : plan.owners()) {
            assertFalse(aggregate.contains(owner.symbol()));
            assertFalse(onLoad.contains(owner.symbol()));
        }
        for (NativeRegistrationControlRoutePlan.Route route
                : routes.routes()) {
            assertFalse(aggregate.contains(route.symbol()));
        }
        for (int ordinal = 1; ordinal < plan.chunks().size(); ordinal++) {
            assertFalse(aggregate.contains(
                    plan.chunks().get(ordinal).symbol()));
            assertFalse(onLoad.contains(
                    plan.chunks().get(ordinal).symbol()));
        }
    }

    @Test
    void aggregateRoutesChunksOwnersAndFailureLeavesAreHashOnlyAndTableFree() {
        HostNativeRegistrationSource.Emission emission =
                NativeRegistrationControlTestFixture.emission(
                        11,
                        "registration-control-surface");
        NativeRegistrationControlTopologyPlan plan =
                emission.topologyPlan();
        String source = emission.source();
        List<String> symbols =
                NativeRegistrationControlTestFixture.controlSymbols(plan);

        assertEquals(symbols.size(), new HashSet<>(symbols).size());
        assertTrue(symbols.stream().allMatch(symbol ->
                symbol.matches("[a-p]{32}")));
        assertTrue(source.contains(
                NativeRegistrationControlCFunctionPolicy.prototype(
                        "static jint "
                                + plan.aggregateSymbol()
                                + "(JavaVM* vm)")));
        assertTrue(source.contains(
                NativeRegistrationControlCFunctionPolicy.prototype(
                        "JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved)")));
        for (NativeRegistrationControlRoutePlan.Route route
                : plan.routePlan().routes()) {
            assertTrue(source.contains(
                    NativeRegistrationControlCFunctionPolicy.prototype(
                            HostNativeRegistrationRouteSource.declaration(
                                    route))));
            assertRouteHasNoRegistrationStateMachine(
                    NativeRegistrationControlTestFixture.function(
                            source,
                            route.symbol()));
        }
        for (NativeRegistrationControlTopologyPlan.Owner owner
                : plan.owners()) {
            assertTrue(source.contains(
                    NativeRegistrationControlCFunctionPolicy.prototype(
                            HostNativeOwnerRegistrationSource.declaration(
                                    owner.symbol()))));
        }
        for (NativeRegistrationControlTopologyPlan.Chunk chunk
                : plan.chunks()) {
            assertTrue(source.contains(
                    NativeRegistrationControlCFunctionPolicy.prototype(
                            HostNativeRegistrationChunkSource.declaration(
                                    chunk.symbol()))));
        }
        for (String failure : plan.failureSymbols().symbols()) {
            assertTrue(source.contains(
                    "static void "
                            + failure
                            + "(JNIEnv* env) __attribute__((noinline, cold));"));
        }

        assertFalse(source.contains("j2ll_register_"));
        assertFalse(source.contains("j2ll_registration_failure_"));
        assertFalse(source.contains("dispatcher"));
        assertFalse(source.matches(
                "(?s).*static\\s+(?:const\\s+)?JNINativeMethod\\s+.*"));
        assertFalse(source.matches(
                "(?s).*static\\s+[^;{}]*\\(\\*[^)]*\\)\\s*\\[[^]]*].*"));
    }

    @Test
    void zeroOwnersHaveNoRouteSurfaceOrConditionalDispatch() {
        HostNativeRegistrationSource.Emission emission =
                NativeRegistrationControlTestFixture.emission(
                        0,
                        "registration-zero-route-na");
        NativeRegistrationControlTopologyPlan plan =
                emission.topologyPlan();
        String onLoad = NativeRegistrationControlTestFixture.functionAtHeader(
                emission.source(),
                "JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved)");

        assertFalse(plan.routePlan().enabled());
        assertTrue(plan.routePlan().routes().isEmpty());
        assertFalse(onLoad.contains("if ("));
        assertEquals(
                1,
                NativeRegistrationControlTestFixture.occurrences(
                        onLoad,
                        plan.aggregateSymbol() + "(vm)"));
    }

    private void assertRouteEdge(
            String source,
            NativeRegistrationControlTopologyPlan plan,
            int ordinal,
            String expectedTarget) {
        NativeRegistrationControlRoutePlan.Route route =
                plan.routePlan().route(ordinal);
        String body = NativeRegistrationControlTestFixture.function(
                source,
                route.symbol());

        assertEquals(
                1,
                NativeRegistrationControlTestFixture.occurrences(
                        body,
                        expectedTarget + "("));
        for (String controlSymbol : NativeRegistrationControlTestFixture
                .controlSymbols(plan)) {
            if (!controlSymbol.equals(route.symbol())
                    && !controlSymbol.equals(expectedTarget)) {
                assertFalse(
                        body.contains(controlSymbol + "("),
                        ordinal + " -> " + controlSymbol);
            }
        }
        assertTrue(body.contains("volatile uintptr_t witness"));
        assertTrue(body.contains("volatile jint result")
                || body.contains("volatile jlong result_wide"));
        int call = body.indexOf(expectedTarget + "(");
        int witnessContinuation = body.indexOf("witness", call);
        int returnOffset = body.lastIndexOf("return ");
        assertTrue(witnessContinuation > call);
        assertTrue(returnOffset > witnessContinuation);
        assertFalse(body.contains("return " + expectedTarget + "("));
        assertRouteHasNoRegistrationStateMachine(body);
    }

    private void assertRouteHasNoRegistrationStateMachine(String body) {
        for (String forbidden : List.of(
                "(*env)->",
                "RegisterNatives",
                "UnregisterNatives",
                "ExceptionCheck",
                "ExceptionOccurred",
                "ExceptionClear",
                "j2ll_registration_resolver_open",
                "j2ll_registration_resolver_close",
                "JNINativeMethod",
                "rollback:",
                "goto rollback",
                "fnPtr",
                "(*")) {
            assertFalse(body.contains(forbidden), forbidden);
        }
        assertFalse(body.matches(
                "(?s).*static\\s+(?:const\\s+)?[^;{}]*\\[[^]]*].*"));
    }
}
