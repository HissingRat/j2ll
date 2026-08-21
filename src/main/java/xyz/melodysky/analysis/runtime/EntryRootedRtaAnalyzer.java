package xyz.melodysky.analysis.runtime;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.objectweb.asm.Type;
import xyz.melodysky.analysis.callgraph.CallGraph;
import xyz.melodysky.analysis.callgraph.CallResolution;
import xyz.melodysky.analysis.hierarchy.ClassHierarchy;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.frontend.classfile.ParsedProgram;

/** Computes the closed-world RTA fixed point from selected native entry methods. */
public final class EntryRootedRtaAnalyzer {
    private final RuntimeAnalysisPipeline runtimeAnalysis = new RuntimeAnalysisPipeline();

    public EntryRootedRtaResult analyze(
            ParsedProgram program,
            ClassHierarchy hierarchy,
            CallGraph cha,
            List<ParsedMethod> entryMethods) {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(hierarchy, "hierarchy");
        Objects.requireNonNull(cha, "cha");
        Objects.requireNonNull(entryMethods, "entryMethods");

        Map<String, ParsedMethod> methods = methodIndex(program);
        TreeSet<String> roots = new TreeSet<>();
        TreeSet<String> seedTypes = new TreeSet<>();
        for (ParsedMethod entry : entryMethods) {
            ParsedMethod canonical = methods.get(entry.methodKey());
            if (canonical == null || !canonical.hasCode()) {
                throw new IllegalArgumentException(
                        "RTA entry is not a code-bearing program method: " + entry.methodKey());
            }
            roots.add(entry.methodKey());
            if (!entry.accessFlags().isStatic()) {
                seedReceiverTypes(seedTypes, entry.owner(), hierarchy);
            }
            for (Type parameter : Type.getArgumentTypes(entry.descriptor())) {
                if (parameter.getSort() == Type.OBJECT) {
                    seedReceiverTypes(seedTypes, parameter.getInternalName(), hierarchy);
                }
            }
        }

        LinkedHashSet<String> reachable = new LinkedHashSet<>(roots);
        RuntimeTypeResult runtimeTypes = runtimeAnalysis.analyze(
                program,
                reachable,
                seedTypes);
        CallGraph effective = cha;
        int iterations = 0;
        int maximumIterations = Math.max(1, methods.size() + 1);
        while (true) {
            iterations++;
            Set<String> reachableSnapshot = Set.copyOf(reachable);
            RtaCallResolver resolver = new RtaCallResolver(hierarchy, runtimeTypes);
            effective = new CallGraph(cha.resolutions().stream()
                    .map(resolution -> reachableSnapshot.contains(callerKey(resolution))
                            ? resolver.refine(resolution)
                            : resolution)
                    .toList());
            LinkedHashSet<String> expanded = new LinkedHashSet<>(reachable);
            for (CallResolution resolution : effective.resolutions()) {
                if (!reachable.contains(callerKey(resolution))) {
                    continue;
                }
                resolution.targets().stream()
                        .filter(target -> !target.unknownExternal())
                        .map(target -> target.displayName())
                        .filter(methods::containsKey)
                        .forEach(expanded::add);
            }
            RuntimeTypeResult expandedTypes = runtimeAnalysis.analyze(
                    program,
                    expanded,
                    seedTypes);
            if (expanded.equals(reachable)
                    && expandedTypes.equals(runtimeTypes)) {
                reachable = expanded;
                runtimeTypes = expandedTypes;
                break;
            }
            if (iterations >= maximumIterations) {
                throw new IllegalStateException(
                        "entry-rooted RTA did not converge within " + maximumIterations + " iterations");
            }
            reachable = expanded;
            runtimeTypes = expandedTypes;
        }

        TreeSet<String> codeMethods = new TreeSet<>();
        methods.values().stream()
                .filter(ParsedMethod::hasCode)
                .map(ParsedMethod::methodKey)
                .forEach(codeMethods::add);
        TreeSet<String> unreachable = new TreeSet<>(codeMethods);
        unreachable.removeAll(reachable);
        return new EntryRootedRtaResult(
                effective,
                runtimeTypes,
                new ReachabilityResult(roots, reachable, unreachable, iterations));
    }

    private Map<String, ParsedMethod> methodIndex(ParsedProgram program) {
        LinkedHashMap<String, ParsedMethod> methods = new LinkedHashMap<>();
        program.classes().stream()
                .flatMap(parsedClass -> parsedClass.methods().stream())
                .sorted(java.util.Comparator.comparing(ParsedMethod::methodKey))
                .forEach(method -> {
                    ParsedMethod previous = methods.put(method.methodKey(), method);
                    if (previous != null) {
                        throw new IllegalArgumentException(
                                "duplicate program method: " + method.methodKey());
                    }
                });
        return Map.copyOf(methods);
    }

    private void seedReceiverTypes(
            Set<String> seedTypes,
            String declaredOwner,
            ClassHierarchy hierarchy) {
        hierarchy.lookupClass(declaredOwner)
                .filter(type -> !type.external()
                        && !type.isInterface()
                        && !type.isAbstract())
                .ifPresent(type -> seedTypes.add(type.internalName()));
        hierarchy.subtypesOf(declaredOwner).stream()
                .filter(type -> hierarchy.lookupClass(type)
                        .filter(candidate -> !candidate.external()
                                && !candidate.isInterface()
                                && !candidate.isAbstract())
                        .isPresent())
                .forEach(seedTypes::add);
    }

    private String callerKey(CallResolution resolution) {
        var site = resolution.callSite();
        return site.callerOwner() + "#" + site.caller().name()
                + "!" + site.caller().descriptor();
    }
}
