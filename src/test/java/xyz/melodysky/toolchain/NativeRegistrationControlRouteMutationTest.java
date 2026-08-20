package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

final class NativeRegistrationControlRouteMutationTest {
    @Test
    void rejectsRootBypassMissingBranchAndWrongRouteEdges() {
        Fixture fixture = fixture();
        NativeRegistrationControlRoutePlan routes =
                fixture.plan().routePlan();
        String root = root(fixture.source());
        String routeZero = HostNativeRegistrationRouteSource.routeCall(
                routes.route(0));
        String routeOne = HostNativeRegistrationRouteSource.routeCall(
                routes.route(1));
        String routeTwo = HostNativeRegistrationRouteSource.routeCall(
                routes.route(2));

        assertRejected(
                fixture,
                replaceOnce(
                        fixture.source(),
                        root,
                        replaceOnce(
                                root,
                                routeZero,
                                fixture.plan().aggregateSymbol() + "(vm)")));
        assertRejected(
                fixture,
                replaceOnce(
                        fixture.source(),
                        root,
                        replaceOnce(root, routeOne, routeZero)));
        assertRejected(
                fixture,
                replaceOnce(
                        fixture.source(),
                        root,
                        replaceOnce(root, routeZero, routeTwo)));
    }

    @Test
    void rejectsEveryWrongRouteGraphEdge() {
        Fixture fixture = fixture();
        NativeRegistrationControlRoutePlan routes =
                fixture.plan().routePlan();

        assertRouteCallRejected(
                fixture,
                routes.route(0),
                fixture.plan().aggregateSymbol() + "(vm)",
                HostNativeRegistrationRouteSource.routeCall(
                        routes.route(2)));
        assertRouteCallRejected(
                fixture,
                routes.route(1),
                HostNativeRegistrationRouteSource.routeCall(
                        routes.route(2)),
                fixture.plan().aggregateSymbol() + "(vm)");
        assertRouteCallRejected(
                fixture,
                routes.route(2),
                fixture.plan().aggregateSymbol() + "(vm)",
                HostNativeRegistrationRouteSource.routeCall(
                        routes.route(1)));
    }

    @Test
    void rejectsTailShortcutsAndMissingVolatileContinuationForEveryRecipe() {
        Fixture fixture = fixture();
        for (NativeRegistrationControlRoutePlan.Route route
                : fixture.plan().routePlan().routes()) {
            String body = NativeRegistrationControlTestFixture.function(
                    fixture.source(),
                    route.symbol());
            String call = targetCall(fixture.plan(), route);
            String continuation =
                    new NativeRegistrationPostCallCSource().callAndReturn(
                            route.postCallRecipe(),
                            call,
                            route.postCallSalt(),
                            "    ");

            assertRejected(
                    fixture,
                    replaceOnce(
                            fixture.source(),
                            body,
                            replaceOnce(
                                    body,
                                    continuation,
                                    "    return " + call + ";\n")));
            assertRejected(
                    fixture,
                    replaceOnce(
                            fixture.source(),
                            body,
                            replaceOnce(
                                    body,
                                    continuation,
                                    continuation.replace("volatile ", ""))));
        }
    }

    @Test
    void rejectsMissingControlAttributesAndRootPostCallContinuation() {
        Fixture fixture = fixture();
        NativeRegistrationControlRoutePlan.Route route =
                fixture.plan().routePlan().route(0);
        String prototype =
                NativeRegistrationControlCFunctionPolicy.prototype(
                        HostNativeRegistrationRouteSource.declaration(
                                route));
        assertRejected(
                fixture,
                replaceOnce(
                        fixture.source(),
                        prototype,
                        HostNativeRegistrationRouteSource.declaration(route)
                                + ";"));
        String rootDeclaration =
                "JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved)";
        String rootPrototype =
                NativeRegistrationControlCFunctionPolicy.prototype(
                        rootDeclaration);
        assertRejected(
                fixture,
                replaceOnce(
                        fixture.source(),
                        rootPrototype,
                        rootDeclaration + ";"));

        String root = root(fixture.source());
        NativeRegistrationControlRoutePlan routes =
                fixture.plan().routePlan();
        String postCall = "    guard += (uintptr_t)(uint32_t)result ^ "
                + NativeRegistrationPostCallCSource.unsignedLong(
                        routes.rootPostCallSalt())
                + ";\n"
                + "    witness ^= guard + (witness >> 11u);\n"
                + "    (void)guard;\n"
                + "    (void)witness;\n";
        assertRejected(
                fixture,
                replaceOnce(
                        fixture.source(),
                        root,
                        replaceOnce(root, postCall, "")));
        assertRejected(
                fixture,
                replaceOnce(
                        fixture.source(),
                        root,
                        replaceOnce(
                                root,
                                "volatile jint result = JNI_ERR;",
                                "jint result = JNI_ERR;")));
    }

    @Test
    void rejectsJniRollbackTableFunctionPointerAndUnknownCallsInsideRoutes() {
        Fixture fixture = fixture();
        NativeRegistrationControlRoutePlan.Route route =
                fixture.plan().routePlan().route(0);
        String body = NativeRegistrationControlTestFixture.function(
                fixture.source(),
                route.symbol());
        String call = targetCall(fixture.plan(), route);
        String continuation =
                new NativeRegistrationPostCallCSource().callAndReturn(
                        route.postCallRecipe(),
                        call,
                        route.postCallSalt(),
                        "    ");

        for (String injection : List.of(
                "    (void)(*env)->ExceptionCheck(env);\n",
                "    (void)(*env)->UnregisterNatives(env, NULL);\n",
                "    goto rollback;\n",
                "    static JNINativeMethod table[1];\n",
                "    jint (*edge)(JavaVM*) = "
                        + fixture.plan().aggregateSymbol()
                        + ";\n",
                "    (void)forbidden_registration_dispatch(vm);\n")) {
            assertRejected(
                    fixture,
                    replaceOnce(
                            fixture.source(),
                            body,
                            replaceOnce(
                                    body,
                                    continuation,
                                    injection + continuation)));
        }
    }

    @Test
    void commentsStringsAndInactivePreprocessorCannotReplaceRealRouteEdges() {
        Fixture fixture = fixture();
        NativeRegistrationControlRoutePlan routes =
                fixture.plan().routePlan();
        NativeRegistrationControlRoutePlan.Route route = routes.route(0);
        String body = NativeRegistrationControlTestFixture.function(
                fixture.source(),
                route.symbol());
        String expected = fixture.plan().aggregateSymbol() + "(vm)";
        String wrong = HostNativeRegistrationRouteSource.routeCall(
                routes.route(2));

        for (String decoy : List.of(
                "    /* " + expected + "; */\n",
                "    const char* decoy = \"" + expected + "\";\n",
                "#if 0\n    (void)" + expected + ";\n#endif\n")) {
            String mutated = replaceOnce(body, expected, wrong);
            mutated = mutated.replace(
                    "    volatile uintptr_t witness",
                    decoy + "    volatile uintptr_t witness");
            assertRejected(
                    fixture,
                    replaceOnce(fixture.source(), body, mutated));
        }
    }

    private Fixture fixture() {
        HostNativeRegistrationSource.Emission emission =
                NativeRegistrationControlTestFixture.emission(
                        11,
                        "registration-route-mutations");
        return new Fixture(emission.source(), emission.topologyPlan());
    }

    private String root(String source) {
        return NativeRegistrationControlTestFixture.functionAtHeader(
                source,
                "JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved)");
    }

    private String targetCall(
            NativeRegistrationControlTopologyPlan plan,
            NativeRegistrationControlRoutePlan.Route route) {
        return route.targetKind()
                        == NativeRegistrationControlRoutePlan.TargetKind.AGGREGATE
                ? plan.aggregateSymbol() + "(vm)"
                : HostNativeRegistrationRouteSource.routeCall(
                        plan.routePlan().route(
                                route.targetRouteOrdinal()));
    }

    private void assertRouteCallRejected(
            Fixture fixture,
            NativeRegistrationControlRoutePlan.Route route,
            String expected,
            String replacement) {
        String body = NativeRegistrationControlTestFixture.function(
                fixture.source(),
                route.symbol());
        assertRejected(
                fixture,
                replaceOnce(
                        fixture.source(),
                        body,
                        replaceOnce(body, expected, replacement)));
    }

    private String replaceOnce(
            String source,
            String before,
            String after) {
        assertEquals(
                1,
                NativeRegistrationControlTestFixture.occurrences(
                        source,
                        before),
                before);
        return source.replace(before, after);
    }

    private void assertRejected(
            Fixture fixture,
            String source) {
        assertThrows(
                IllegalStateException.class,
                () -> new NativeRegistrationControlSourceVerifier().verify(
                        source,
                        fixture.plan()));
    }

    private record Fixture(
            String source,
            NativeRegistrationControlTopologyPlan plan) {}
}
