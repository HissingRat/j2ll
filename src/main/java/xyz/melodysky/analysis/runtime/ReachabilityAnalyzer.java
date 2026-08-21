package xyz.melodysky.analysis.runtime;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import xyz.melodysky.analysis.callgraph.CallGraph;
import xyz.melodysky.analysis.callgraph.CallResolution;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.frontend.classfile.ParsedProgram;

/** Computes stable method reachability through known call-graph targets. */
public final class ReachabilityAnalyzer {
    public ReachabilityResult analyze(
            ParsedProgram program,
            CallGraph callGraph,
            Set<String> entryMethodKeys) {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(callGraph, "callGraph");
        Objects.requireNonNull(entryMethodKeys, "entryMethodKeys");
        Map<String, ParsedMethod> methods = methodIndex(program);
        TreeSet<String> roots = new TreeSet<>(entryMethodKeys);
        for (String root : roots) {
            ParsedMethod method = methods.get(root);
            if (method == null || !method.hasCode()) {
                throw new IllegalArgumentException(
                        "reachability entry is not a code-bearing program method: " + root);
            }
        }

        Map<String, java.util.List<CallResolution>> callsByCaller = new LinkedHashMap<>();
        for (CallResolution resolution : callGraph.resolutions()) {
            callsByCaller.computeIfAbsent(
                            callerKey(resolution),
                            ignored -> new java.util.ArrayList<>())
                    .add(resolution);
        }
        LinkedHashSet<String> reachable = new LinkedHashSet<>();
        ArrayDeque<String> work = new ArrayDeque<>(roots);
        while (!work.isEmpty()) {
            String caller = work.removeFirst();
            if (!reachable.add(caller)) {
                continue;
            }
            for (CallResolution resolution : callsByCaller.getOrDefault(caller, java.util.List.of())) {
                resolution.targets().stream()
                        .filter(target -> !target.unknownExternal())
                        .map(target -> target.displayName())
                        .filter(methods::containsKey)
                        .filter(target -> !reachable.contains(target))
                        .forEach(work::addLast);
            }
        }
        TreeSet<String> codeMethods = new TreeSet<>();
        methods.values().stream()
                .filter(ParsedMethod::hasCode)
                .map(ParsedMethod::methodKey)
                .forEach(codeMethods::add);
        TreeSet<String> unreachable = new TreeSet<>(codeMethods);
        unreachable.removeAll(reachable);
        return new ReachabilityResult(roots, reachable, unreachable, 0);
    }

    private Map<String, ParsedMethod> methodIndex(ParsedProgram program) {
        LinkedHashMap<String, ParsedMethod> result = new LinkedHashMap<>();
        program.classes().stream()
                .flatMap(parsedClass -> parsedClass.methods().stream())
                .sorted(java.util.Comparator.comparing(ParsedMethod::methodKey))
                .forEach(method -> {
                    ParsedMethod previous = result.put(method.methodKey(), method);
                    if (previous != null) {
                        throw new IllegalArgumentException(
                                "duplicate program method: " + method.methodKey());
                    }
                });
        return Map.copyOf(result);
    }

    private String callerKey(CallResolution resolution) {
        var site = resolution.callSite();
        return site.callerOwner() + "#" + site.caller().name()
                + "!" + site.caller().descriptor();
    }
}
