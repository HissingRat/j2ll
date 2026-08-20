package xyz.melodysky.toolchain;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** Emits the bounded non-tail entry routes for non-empty registration. */
final class HostNativeRegistrationRouteSource {
    String emit(
            NativeRegistrationControlRoutePlan plan,
            String aggregateSymbol) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(aggregateSymbol, "aggregateSymbol");
        if (!plan.enabled()) {
            return "";
        }
        StringBuilder source = new StringBuilder();
        for (NativeRegistrationControlRoutePlan.Route route
                : plan.routes()) {
            source.append(NativeRegistrationControlCFunctionPolicy.prototype(
                    declaration(route)))
                    .append('\n');
        }
        source.append('\n');
        for (NativeRegistrationControlRoutePlan.Route route
                : plan.routes()) {
            appendDefinition(source, plan, route, aggregateSymbol);
        }
        return source.toString();
    }

    private void appendDefinition(
            StringBuilder source,
            NativeRegistrationControlRoutePlan plan,
            NativeRegistrationControlRoutePlan.Route route,
            String aggregateSymbol) {
        source.append(
                        NativeRegistrationControlCFunctionPolicy
                                .definitionHeader(declaration(route)))
                .append('\n')
                .append("    volatile uintptr_t witness = guard\n")
                .append("            ^ (uintptr_t)(void*)vm\n")
                .append("            ^ (uintptr_t)reserved\n")
                .append("            ^ ")
                .append(NativeRegistrationPostCallCSource.unsignedLong(
                        route.witnessSalt()))
                .append(";\n");
        String call = route.targetKind()
                        == NativeRegistrationControlRoutePlan.TargetKind.AGGREGATE
                ? aggregateSymbol + "(vm)"
                : routeCall(plan.route(route.targetRouteOrdinal()));
        source.append(new NativeRegistrationPostCallCSource().callAndReturn(
                route.postCallRecipe(),
                call,
                route.postCallSalt(),
                "    "))
                .append("}\n\n");
    }

    static String declaration(
            NativeRegistrationControlRoutePlan.Route route) {
        return "static jint " + route.symbol() + "("
                + parameters(route.parameterOrder()) + ")";
    }

    static String routeCall(
            NativeRegistrationControlRoutePlan.Route route) {
        return route.symbol() + "("
                + arguments(route.parameterOrder()) + ")";
    }

    private static String parameters(
            List<NativeRegistrationControlRoutePlan.Parameter> order) {
        return order.stream()
                .map(NativeRegistrationControlRoutePlan.Parameter::declaration)
                .collect(Collectors.joining(", "));
    }

    private static String arguments(
            List<NativeRegistrationControlRoutePlan.Parameter> order) {
        return order.stream()
                .map(NativeRegistrationControlRoutePlan.Parameter::argument)
                .collect(Collectors.joining(", "));
    }
}
