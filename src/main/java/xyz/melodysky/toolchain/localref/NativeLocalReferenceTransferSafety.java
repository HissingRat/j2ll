package xyz.melodysky.toolchain.localref;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Verifies that every terminal ownership-transfer component reaches a release
 * or activation-exit sink.
 *
 * <p>A loop-carried handle may circulate through an SSA phi any number of
 * times without allocating another handle. Such a transfer SCC is safe when
 * every way out eventually terminates in a verified sink. A closed SCC with
 * no sink still fails closed.</p>
 */
final class NativeLocalReferenceTransferSafety {
    boolean hasVerifiedSink(
            String source,
            Map<String, Set<String>> transfers,
            Set<String> sinks) {
        Set<String> reachable = reachable(source, transfers);
        List<Set<String>> components =
                stronglyConnectedComponents(reachable, transfers);
        Map<String, Integer> componentByValue = new LinkedHashMap<>();
        for (int index = 0; index < components.size(); index++) {
            for (String value : components.get(index)) {
                componentByValue.put(value, index);
            }
        }
        for (int index = 0; index < components.size(); index++) {
            int componentIndex = index;
            Set<String> component = components.get(index);
            boolean terminal = component.stream()
                    .flatMap(value -> transfers
                            .getOrDefault(value, Set.of())
                            .stream())
                    .filter(reachable::contains)
                    .allMatch(target ->
                            componentByValue.get(target)
                                    == componentIndex);
            if (terminal && component.stream().noneMatch(sinks::contains)) {
                return false;
            }
        }
        return true;
    }

    private Set<String> reachable(
            String source,
            Map<String, Set<String>> transfers) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        ArrayDeque<String> work = new ArrayDeque<>();
        work.add(source);
        while (!work.isEmpty()) {
            String value = work.removeFirst();
            if (result.add(value)) {
                transfers.getOrDefault(value, Set.of())
                        .forEach(work::addLast);
            }
        }
        return Set.copyOf(result);
    }

    private List<Set<String>> stronglyConnectedComponents(
            Set<String> values,
            Map<String, Set<String>> transfers) {
        ArrayList<String> finishOrder = new ArrayList<>();
        LinkedHashSet<String> visited = new LinkedHashSet<>();
        values.stream()
                .sorted()
                .forEach(value -> finish(
                        value,
                        values,
                        transfers,
                        visited,
                        finishOrder));

        Map<String, Set<String>> reverse =
                reverse(values, transfers);
        visited.clear();
        ArrayList<Set<String>> components = new ArrayList<>();
        for (int index = finishOrder.size() - 1; index >= 0; index--) {
            String value = finishOrder.get(index);
            if (visited.contains(value)) {
                continue;
            }
            LinkedHashSet<String> component = new LinkedHashSet<>();
            collect(value, reverse, visited, component);
            components.add(Set.copyOf(component));
        }
        return List.copyOf(components);
    }

    private void finish(
            String value,
            Set<String> values,
            Map<String, Set<String>> transfers,
            Set<String> visited,
            List<String> order) {
        if (!visited.add(value)) {
            return;
        }
        transfers.getOrDefault(value, Set.of()).stream()
                .filter(values::contains)
                .sorted()
                .forEach(target -> finish(
                        target,
                        values,
                        transfers,
                        visited,
                        order));
        order.add(value);
    }

    private Map<String, Set<String>> reverse(
            Set<String> values,
            Map<String, Set<String>> transfers) {
        LinkedHashMap<String, LinkedHashSet<String>> reverse =
                new LinkedHashMap<>();
        values.stream().sorted().forEach(value ->
                reverse.put(value, new LinkedHashSet<>()));
        values.forEach(source -> transfers
                .getOrDefault(source, Set.of())
                .stream()
                .filter(values::contains)
                .forEach(target -> reverse.get(target).add(source)));
        LinkedHashMap<String, Set<String>> result = new LinkedHashMap<>();
        reverse.forEach((value, sources) ->
                result.put(value, Set.copyOf(sources)));
        return Map.copyOf(result);
    }

    private void collect(
            String value,
            Map<String, Set<String>> reverse,
            Set<String> visited,
            Set<String> component) {
        if (!visited.add(value)) {
            return;
        }
        component.add(value);
        reverse.getOrDefault(value, Set.of()).stream()
                .sorted()
                .forEach(source -> collect(
                        source,
                        reverse,
                        visited,
                        component));
    }
}
