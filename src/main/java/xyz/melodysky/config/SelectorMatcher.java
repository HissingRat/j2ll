package xyz.melodysky.config;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticLocation;
import xyz.melodysky.diagnostic.DiagnosticStage;
import xyz.melodysky.frontend.classfile.ParsedClass;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.frontend.classfile.ParsedProgram;
import xyz.melodysky.pipeline.MethodEligibility;

public final class SelectorMatcher {
    public SelectorMatchResult expand(ParsedProgram program, List<Selector> whiteList, List<Selector> blackList) {
        List<Selector> effectiveWhiteList = whiteList.isEmpty() ? List.of(Selector.implicitAll()) : whiteList;
        Map<String, MatchedMethod> requested = new LinkedHashMap<>();
        Map<String, MethodEligibility> ineligible = new LinkedHashMap<>();
        ArrayList<Diagnostic> diagnostics = new ArrayList<>();

        for (Selector selector : effectiveWhiteList) {
            SelectorExpansion expansion = expandSelector(program, selector);
            if (expansion.matchedMethods().isEmpty() && !selector.raw().equals("<implicit-all>")) {
                diagnostics.add(Diagnostic.error(
                        DiagnosticStage.CONFIG,
                        ConfigDiagnostics.UNMATCHED_WHITELIST_SELECTOR,
                        "whiteList selector matched no class or method: " + selector.raw()));
                continue;
            }
            for (ParsedMethod method : expansion.matchedMethods()) {
                MethodEligibility eligibility = eligibilityFor(method, selector.raw());
                if (eligibility.requested()) {
                    requested.put(method.methodKey(), new MatchedMethod(method, selector.raw()));
                } else {
                    ineligible.put(method.methodKey(), eligibility);
                }
            }
        }

        Map<String, MethodEligibility> excluded = new LinkedHashMap<>();
        Set<String> blacklistedKeys = new LinkedHashSet<>();
        for (Selector selector : blackList) {
            SelectorExpansion expansion = expandSelector(program, selector);
            if (expansion.matchedMethods().isEmpty()) {
                diagnostics.add(Diagnostic.warning(
                        DiagnosticStage.CONFIG,
                        ConfigDiagnostics.UNMATCHED_BLACKLIST_SELECTOR,
                        "blackList selector matched no class or method: " + selector.raw()));
                continue;
            }
            for (ParsedMethod method : expansion.matchedMethods()) {
                blacklistedKeys.add(method.methodKey());
                excluded.put(method.methodKey(), MethodEligibility.excluded(
                        method.owner(),
                        method.name(),
                        method.descriptor(),
                        selector.raw(),
                        "BLACKLISTED",
                        "method excluded by blackList selector"));
            }
        }

        for (String methodKey : blacklistedKeys) {
            requested.remove(methodKey);
            ineligible.remove(methodKey);
        }

        List<ParsedMethod> requestedMethods = requested.values().stream()
                .map(MatchedMethod::method)
                .sorted(METHOD_ORDER)
                .toList();
        List<MethodEligibility> ineligibleMethods = ineligible.values().stream()
                .sorted(ELIGIBILITY_ORDER)
                .toList();
        List<MethodEligibility> excludedMethods = excluded.values().stream()
                .sorted(ELIGIBILITY_ORDER)
                .toList();
        return new SelectorMatchResult(requestedMethods, ineligibleMethods, excludedMethods, diagnostics);
    }

    private SelectorExpansion expandSelector(ParsedProgram program, Selector selector) {
        ArrayList<ParsedMethod> methods = new ArrayList<>();
        for (ParsedClass parsedClass : program.classes()) {
            if (!selector.matchesClass(parsedClass.internalName())) {
                continue;
            }
            for (ParsedMethod method : parsedClass.methods()) {
                if (!selector.isMethodSelector() || selector.matchesMethod(method)) {
                    methods.add(method);
                }
            }
        }
        methods.sort(METHOD_ORDER);
        return new SelectorExpansion(List.copyOf(methods));
    }

    private MethodEligibility eligibilityFor(ParsedMethod method, String selector) {
        if (method.accessFlags().isAbstract()) {
            return MethodEligibility.ineligible(
                    method.owner(),
                    method.name(),
                    method.descriptor(),
                    selector,
                    "ABSTRACT_METHOD",
                    "abstract method has no lowerable body");
        }
        if (method.accessFlags().isNative()) {
            return MethodEligibility.ineligible(
                    method.owner(),
                    method.name(),
                    method.descriptor(),
                    selector,
                    "ALREADY_NATIVE",
                    "already-native method does not need bytecode lowering");
        }
        if (!method.hasCode()) {
            return MethodEligibility.ineligible(
                    method.owner(),
                    method.name(),
                    method.descriptor(),
                    selector,
                    "NO_CODE",
                    "method has no Code attribute");
        }
        return MethodEligibility.requested(method.owner(), method.name(), method.descriptor(), selector);
    }

    private record SelectorExpansion(List<ParsedMethod> matchedMethods) {
    }

    private record MatchedMethod(ParsedMethod method, String selector) {
    }

    private static final Comparator<ParsedMethod> METHOD_ORDER = Comparator
            .comparing(ParsedMethod::owner)
            .thenComparing(ParsedMethod::name)
            .thenComparing(ParsedMethod::descriptor);

    private static final Comparator<MethodEligibility> ELIGIBILITY_ORDER = Comparator
            .comparing(MethodEligibility::owner)
            .thenComparing(MethodEligibility::name)
            .thenComparing(MethodEligibility::descriptor);
}
