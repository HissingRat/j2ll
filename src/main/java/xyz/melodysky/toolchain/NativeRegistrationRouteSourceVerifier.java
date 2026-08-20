package xyz.melodysky.toolchain;

/** Exact source gate for the non-tail JNI entry route graph. */
final class NativeRegistrationRouteSourceVerifier {
    void verify(
            NativeRegistrationControlSourceIndex index,
            NativeRegistrationControlTopologyPlan topologyPlan) {
        NativeRegistrationControlRoutePlan plan =
                topologyPlan.routePlan();
        verifyJniOnLoad(index, topologyPlan);
        if (!plan.enabled()) {
            return;
        }
        for (NativeRegistrationControlRoutePlan.Route route
                : plan.routes()) {
            String declaration =
                    HostNativeRegistrationRouteSource.declaration(route);
            if (index.codeCountExactAtIdentifier(
                            NativeRegistrationControlCFunctionPolicy
                                    .prototype(declaration),
                            route.symbol()) != 1) {
                fail("ROUTE_FUNCTION_POLICY_CLOSURE");
            }
            String body = index.functionBody(
                    NativeRegistrationControlCFunctionPolicy
                            .definitionHeader(declaration));
            if (body == null || !body.equals(expectedRouteBody(
                    plan,
                    topologyPlan.aggregateSymbol(),
                    route))) {
                fail("ROUTE_CLOSED_SCHEMA");
            }
        }
        verifyCallGraph(index, topologyPlan);
    }

    private void verifyJniOnLoad(
            NativeRegistrationControlSourceIndex index,
            NativeRegistrationControlTopologyPlan topologyPlan) {
        String declaration =
                "JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved)";
        if (index.codeCountExactAtIdentifier(
                        NativeRegistrationControlCFunctionPolicy
                                .prototype(declaration),
                        "JNI_OnLoad") != 1) {
            fail("JNI_ONLOAD_FUNCTION_POLICY_CLOSURE");
        }
        String body = index.functionBody(
                NativeRegistrationControlCFunctionPolicy
                        .definitionHeader(declaration));
        NativeRegistrationControlRoutePlan plan =
                topologyPlan.routePlan();
        String expected;
        if (!plan.enabled()) {
            expected = "\n    (void)reserved;\n"
                    + "    volatile jint result = "
                    + topologyPlan.aggregateSymbol()
                    + "(vm);\n"
                    + "    return result;\n";
        } else {
            expected = expectedRootBody(plan);
        }
        if (body == null || !body.equals(expected)) {
            fail("JNI_ONLOAD_ROUTE_CLOSURE");
        }
    }

    private String expectedRouteBody(
            NativeRegistrationControlRoutePlan plan,
            String aggregateSymbol,
            NativeRegistrationControlRoutePlan.Route route) {
        String call = route.targetKind()
                        == NativeRegistrationControlRoutePlan.TargetKind.AGGREGATE
                ? aggregateSymbol + "(vm)"
                : HostNativeRegistrationRouteSource.routeCall(
                        plan.route(route.targetRouteOrdinal()));
        return "\n    volatile uintptr_t witness = guard\n"
                + "            ^ (uintptr_t)(void*)vm\n"
                + "            ^ (uintptr_t)reserved\n"
                + "            ^ "
                + NativeRegistrationPostCallCSource.unsignedLong(
                        route.witnessSalt())
                + ";\n"
                + expectedPostCall(
                        route.postCallRecipe(),
                        call,
                        route.postCallSalt());
    }

    private String expectedPostCall(
            NativeRegistrationPostCallRecipe recipe,
            String call,
            long postCallSalt) {
        String salt = NativeRegistrationPostCallCSource.unsignedLong(
                postCallSalt);
        return switch (recipe) {
            case XOR_JINT -> "    volatile jint result = " + call + ";\n"
                    + "    witness ^= (uintptr_t)(uint32_t)result + "
                    + salt + ";\n"
                    + "    (void)witness;\n"
                    + "    return result;\n";
            case ADD_JLONG -> "    volatile jlong result_wide = (jlong)("
                    + call + ");\n"
                    + "    witness += ((uintptr_t)(uint64_t)result_wide ^ "
                    + salt + ");\n"
                    + "    witness ^= (witness >> 7u);\n"
                    + "    (void)witness;\n"
                    + "    return (jint)result_wide;\n";
            case MIRROR_JINT -> "    volatile jint result = " + call + ";\n"
                    + "    volatile uintptr_t mirror = (uintptr_t)(uint32_t)result ^ "
                    + salt + ";\n"
                    + "    witness = (witness + mirror) ^ ("
                    + salt + " >> 1u);\n"
                    + "    mirror += witness ^ " + salt + ";\n"
                    + "    (void)mirror;\n"
                    + "    (void)witness;\n"
                    + "    return result;\n";
        };
    }

    private String expectedRootBody(
            NativeRegistrationControlRoutePlan plan) {
        return "\n    volatile uintptr_t guard = (uintptr_t)(void*)&guard\n"
                + "            ^ (uintptr_t)(void*)vm\n"
                + "            ^ (uintptr_t)reserved\n"
                + "            ^ "
                + NativeRegistrationPostCallCSource.unsignedLong(
                        plan.rootGuardSalt())
                + ";\n"
                + "    volatile uintptr_t witness = guard ^ "
                + NativeRegistrationPostCallCSource.unsignedLong(
                        plan.rootSelectorSalt())
                + ";\n"
                + "    volatile jint result = JNI_ERR;\n"
                + "    if ((((witness >> " + plan.rootSelectorShift()
                + "u) ^ guard) & (uintptr_t)1u) == (uintptr_t)0u) {\n"
                + "        result = "
                + HostNativeRegistrationRouteSource.routeCall(plan.route(0))
                + ";\n"
                + "    } else {\n"
                + "        result = "
                + HostNativeRegistrationRouteSource.routeCall(plan.route(1))
                + ";\n"
                + "    }\n"
                + "    guard += (uintptr_t)(uint32_t)result ^ "
                + NativeRegistrationPostCallCSource.unsignedLong(
                        plan.rootPostCallSalt())
                + ";\n"
                + "    witness ^= guard + (witness >> 11u);\n"
                + "    (void)guard;\n"
                + "    (void)witness;\n"
                + "    return result;\n";
    }

    private void verifyCallGraph(
            NativeRegistrationControlSourceIndex index,
            NativeRegistrationControlTopologyPlan topologyPlan) {
        NativeRegistrationControlRoutePlan plan =
                topologyPlan.routePlan();
        String rootBody = index.functionBody(
                NativeRegistrationControlCFunctionPolicy.definitionHeader(
                        "JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved)"));
        NativeRegistrationControlSourceIndex rootIndex =
                new NativeRegistrationControlSourceIndex(rootBody);
        if (rootIndex.identifierCount(topologyPlan.aggregateSymbol()) != 0
                || rootIndex.identifierCount(plan.route(0).symbol()) != 1
                || rootIndex.identifierCount(plan.route(1).symbol()) != 1
                || rootIndex.identifierCount(plan.route(2).symbol()) != 0) {
            fail("JNI_ONLOAD_ROUTE_GRAPH");
        }
        for (NativeRegistrationControlRoutePlan.Route route
                : plan.routes()) {
            String body = index.functionBody(
                    NativeRegistrationControlCFunctionPolicy.definitionHeader(
                            HostNativeRegistrationRouteSource.declaration(route)));
            NativeRegistrationControlSourceIndex bodyIndex =
                    new NativeRegistrationControlSourceIndex(body);
            int aggregateCalls = route.targetKind()
                            == NativeRegistrationControlRoutePlan.TargetKind.AGGREGATE
                    ? 1
                    : 0;
            if (bodyIndex.identifierCount(topologyPlan.aggregateSymbol())
                    != aggregateCalls) {
                fail("ROUTE_AGGREGATE_CALL_CLOSURE");
            }
        }
    }

    private void fail(String code) {
        throw new IllegalStateException(
                "native registration control topology audit failed: "
                        + code);
    }
}
