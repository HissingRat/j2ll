package xyz.melodysky.analysis.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import xyz.melodysky.analysis.hierarchy.ClassHierarchy;
import xyz.melodysky.analysis.method.KnownJvmCallbackObserver;
import xyz.melodysky.analysis.reflection.ReflectionPlan;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.frontend.classfile.ParsedProgram;

/** Plans the conservative program entries from which reachability is computed. */
public final class ProgramEntryPointPlanner {
    private final KnownJvmCallbackObserver callbackObserver =
            new KnownJvmCallbackObserver();

    public List<ParsedMethod> plan(
            ParsedProgram program,
            ClassHierarchy hierarchy,
            List<ParsedMethod> selectedMethods,
            ReflectionPlan reflectionPlan) {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(hierarchy, "hierarchy");
        Objects.requireNonNull(selectedMethods, "selectedMethods");
        Objects.requireNonNull(reflectionPlan, "reflectionPlan");

        Map<String, ParsedMethod> methods = methodIndex(program);
        TreeSet<String> roots = new TreeSet<>();
        selectedMethods.forEach(method -> addCodeMethod(
                roots,
                methods,
                method.methodKey(),
                "selected entry"));

        if (reflectionPlan.hasUnsupportedSites()) {
            methods.values().stream()
                    .filter(ParsedMethod::hasCode)
                    .map(ParsedMethod::methodKey)
                    .forEach(roots::add);
        } else {
            methods.values().stream()
                    .filter(ParsedMethod::hasCode)
                    .filter(method -> !method.accessFlags().isPrivate()
                            || method.name().equals("<clinit>")
                            || callbackObserver.observedContract(method, hierarchy).isPresent())
                    .map(ParsedMethod::methodKey)
                    .forEach(roots::add);
            reflectionPlan.reachableMethods().forEach(target -> addCodeMethod(
                    roots,
                    methods,
                    target.methodKey(),
                    "resolved reflection entry"));
        }
        return roots.stream().map(methods::get).toList();
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

    private void addCodeMethod(
            Set<String> roots,
            Map<String, ParsedMethod> methods,
            String methodKey,
            String source) {
        ParsedMethod method = methods.get(methodKey);
        if (method == null || !method.hasCode()) {
            throw new IllegalArgumentException(
                    source + " is not a code-bearing program method: " + methodKey);
        }
        roots.add(methodKey);
    }
}
