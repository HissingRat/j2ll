package xyz.melodysky.config;

import java.util.List;
import java.util.Objects;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.pipeline.MethodEligibility;

public record SelectorMatchResult(
        List<ParsedMethod> requestedMethods,
        List<MethodEligibility> ineligible,
        List<MethodEligibility> excluded,
        List<Diagnostic> diagnostics) {
    public SelectorMatchResult {
        requestedMethods = List.copyOf(Objects.requireNonNull(requestedMethods, "requestedMethods"));
        ineligible = List.copyOf(Objects.requireNonNull(ineligible, "ineligible"));
        excluded = List.copyOf(Objects.requireNonNull(excluded, "excluded"));
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }
}
