package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class NativeRegistrationAssemblyShapeAssertions {
    private static final Pattern CALL = Pattern.compile(
            "(?m)^\\s*(?:callq?|bl)\\s+([^\\s,;#]+)");
    private static final Pattern DIRECT_BRANCH = Pattern.compile(
            "(?m)^\\s*(?:jmpq?|b)\\s+([^\\s,;#]+)");

    void assertShape(
            String assembly,
            TargetTriple target,
            NativeRegistrationControlTopologyPlan plan) {
        NativeRegistrationControlRoutePlan routes = plan.routePlan();
        Set<String> controls = new HashSet<>(
                NativeRegistrationControlTestFixture.controlSymbols(plan));
        controls.add("JNI_OnLoad");

        String root = function(assembly, "JNI_OnLoad", target);
        assertCallMultiset(
                root,
                List.of(
                        routes.route(0).symbol(),
                        routes.route(1).symbol()),
                target,
                "JNI_OnLoad");
        assertProtected(root, controls, target, "JNI_OnLoad");
        assertFalse(
                calls(root).contains(plan.aggregateSymbol()),
                target.directoryName() + ": root bypassed routes");
        assertFalse(
                calls(root).contains(routes.route(2).symbol()),
                target.directoryName() + ": root called route 2 directly");

        assertRoute(
                assembly,
                target,
                routes.route(0),
                plan.aggregateSymbol(),
                controls);
        assertRoute(
                assembly,
                target,
                routes.route(1),
                routes.route(2).symbol(),
                controls);
        assertRoute(
                assembly,
                target,
                routes.route(2),
                plan.aggregateSymbol(),
                controls);

        for (int ordinal = 0; ordinal < plan.chunks().size(); ordinal++) {
            NativeRegistrationControlTopologyPlan.Chunk chunk =
                    plan.chunks().get(ordinal);
            ArrayList<String> expected = new ArrayList<>(
                    chunk.owners().stream()
                            .map(NativeRegistrationControlTopologyPlan.Owner::symbol)
                            .toList());
            if (ordinal + 1 < plan.chunks().size()) {
                expected.add(plan.chunks().get(ordinal + 1).symbol());
            }
            String body = function(assembly, chunk.symbol(), target);
            assertEquals(
                    expected,
                    calls(body),
                    target.directoryName() + ": chunk " + ordinal);
            assertProtected(
                    body,
                    controls,
                    target,
                    "chunk " + ordinal);
        }

        String aggregate = function(
                assembly,
                plan.aggregateSymbol(),
                target);
        List<String> aggregateCalls = calls(aggregate);
        assertEquals(
                1,
                occurrences(
                        aggregateCalls,
                        plan.chunks().get(0).symbol()),
                target.directoryName() + ": aggregate -> first chunk");
        for (NativeRegistrationControlRoutePlan.Route route
                : routes.routes()) {
            assertFalse(
                    aggregateCalls.contains(route.symbol()),
                    target.directoryName()
                            + ": aggregate called route "
                            + route.ordinal());
        }
        for (int ordinal = 1; ordinal < plan.chunks().size(); ordinal++) {
            assertFalse(
                    aggregateCalls.contains(
                            plan.chunks().get(ordinal).symbol()),
                    target.directoryName()
                            + ": aggregate skipped to chunk "
                            + ordinal);
        }
    }

    private void assertRoute(
            String assembly,
            TargetTriple target,
            NativeRegistrationControlRoutePlan.Route route,
            String expectedTarget,
            Set<String> controls) {
        String body = function(assembly, route.symbol(), target);
        assertEquals(
                List.of(expectedTarget),
                calls(body),
                target.directoryName() + ": route " + route.ordinal());
        assertProtected(
                body,
                controls,
                target,
                "route " + route.ordinal());
    }

    private void assertCallMultiset(
            String body,
            List<String> expected,
            TargetTriple target,
            String caller) {
        assertEquals(
                frequencies(expected),
                frequencies(calls(body)),
                target.directoryName() + ": " + caller + " direct calls");
    }

    private void assertProtected(
            String body,
            Set<String> controls,
            TargetTriple target,
            String caller) {
        Matcher branches = DIRECT_BRANCH.matcher(body);
        while (branches.find()) {
            String destination = normalizeTarget(branches.group(1));
            assertFalse(
                    controls.contains(destination),
                    target.directoryName()
                            + ": "
                            + caller
                            + " tail-branched to "
                            + destination);
        }
        assertFalse(
                body.contains("OUTLINED_FUNCTION"),
                target.directoryName()
                        + ": "
                        + caller
                        + " gained a machine-outliner edge\n"
                        + body);
    }

    private String function(
            String assembly,
            String symbol,
            TargetTriple target) {
        Pattern label = Pattern.compile(
                "(?m)^_?" + Pattern.quote(symbol) + ":(?:\\s|$)");
        Matcher matcher = label.matcher(assembly);
        assertTrue(
                matcher.find(),
                target.directoryName() + ": missing function " + symbol);
        int endMarker = assembly.indexOf("-- End function", matcher.start());
        assertTrue(
                endMarker > matcher.start(),
                target.directoryName() + ": incomplete function " + symbol);
        int end = assembly.indexOf('\n', endMarker);
        return assembly.substring(
                matcher.start(),
                end < 0 ? assembly.length() : end);
    }

    private List<String> calls(String body) {
        ArrayList<String> calls = new ArrayList<>();
        Matcher matcher = CALL.matcher(body);
        while (matcher.find()) {
            calls.add(normalizeTarget(matcher.group(1)));
        }
        return List.copyOf(calls);
    }

    private String normalizeTarget(String target) {
        String normalized = target;
        int suffix = normalized.indexOf('@');
        if (suffix >= 0) {
            normalized = normalized.substring(0, suffix);
        }
        return normalized.startsWith("_")
                ? normalized.substring(1)
                : normalized;
    }

    private Map<String, Integer> frequencies(List<String> values) {
        HashMap<String, Integer> result = new HashMap<>();
        values.forEach(value -> result.merge(value, 1, Integer::sum));
        return Map.copyOf(result);
    }

    private int occurrences(
            List<String> values,
            String expected) {
        return (int) values.stream().filter(expected::equals).count();
    }
}
