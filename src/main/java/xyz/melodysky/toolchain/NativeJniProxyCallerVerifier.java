package xyz.melodysky.toolchain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exact topology callers plus explicitly planned semantic-body callers. */
final class NativeJniProxyCallerVerifier {
    List<String> validate(
            String methodKey,
            NativeJniEntryTopology topology,
            String proxy,
            String body,
            NativeLlvmSymbolIndex symbols,
            List<String> expectedSemanticCallers) {
        ArrayList<String> issues = new ArrayList<>();
        Map<String, List<String>> expected = expectedCallers(
                topology,
                proxy,
                body);
        Set<String> topologyFunctions = java.util.stream.Stream
                .concat(
                        java.util.stream.Stream.of(proxy),
                        topology.bridgeSymbols().stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        expected.forEach((target, callers) -> {
            List<String> actual = symbols.callers(target);
            List<String> relevant = target.equals(body)
                    ? actual.stream()
                            .filter(topologyFunctions::contains)
                            .toList()
                    : actual;
            if (!relevant.stream().sorted().toList()
                    .equals(callers.stream().sorted().toList())) {
                issues.add(methodKey
                        + ":LLVM_JNI_PROXY_CALLER_CLOSURE_MISMATCH");
            }
            if (target.equals(body)
                    && !actual.stream()
                            .filter(caller -> !topologyFunctions.contains(caller))
                            .sorted()
                            .toList()
                            .equals(expectedSemanticCallers.stream()
                                    .sorted()
                                    .toList())) {
                issues.add(methodKey
                        + ":LLVM_JNI_PROXY_SEMANTIC_CALLER_STATE_MISMATCH");
            }
        });
        return issues.stream().distinct().sorted().toList();
    }

    private Map<String, List<String>> expectedCallers(
            NativeJniEntryTopology topology,
            String proxy,
            String body) {
        LinkedHashMap<String, ArrayList<String>> result = new LinkedHashMap<>();
        result.put(proxy, new ArrayList<>());
        List<String> bridges = topology.bridgeSymbols();
        switch (topology.shape()) {
            case DIRECT_CANONICAL -> add(result, proxy, body);
            case SINGLE_PERMUTING_BRIDGE -> {
                add(result, proxy, bridges.get(0));
                add(result, bridges.get(0), body);
            }
            case DOUBLE_PERMUTING_BRIDGE -> {
                add(result, proxy, bridges.get(0));
                add(result, bridges.get(0), bridges.get(1));
                add(result, bridges.get(1), body);
            }
            case BRANCHED_PERMUTING_BRIDGE -> {
                add(result, proxy, bridges.get(0));
                add(result, proxy, bridges.get(1));
                add(result, bridges.get(0), body);
                add(result, bridges.get(1), bridges.get(2));
                add(result, bridges.get(2), body);
            }
        }
        LinkedHashMap<String, List<String>> stable = new LinkedHashMap<>();
        result.forEach((target, callers) ->
                stable.put(target, List.copyOf(callers)));
        return Map.copyOf(stable);
    }

    private void add(
            Map<String, ArrayList<String>> callers,
            String caller,
            String target) {
        callers.computeIfAbsent(target, ignored -> new ArrayList<>())
                .add(caller);
    }
}
