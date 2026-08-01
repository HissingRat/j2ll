package xyz.melodysky.analysis.method;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import xyz.melodysky.config.Selector;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticLocation;
import xyz.melodysky.diagnostic.DiagnosticStage;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.frontend.classfile.ParsedProgram;

/** Resolves config-validated exact selectors against input-base public Code methods. */
public final class PublicMethodInternalizationAllowListResolver {
    public Result resolve(
            ParsedProgram inputProgram,
            List<Selector> selectors) {
        Objects.requireNonNull(inputProgram, "inputProgram");
        Objects.requireNonNull(selectors, "selectors");
        LinkedHashSet<NativeMethodId> methods = new LinkedHashSet<>();
        ArrayList<Diagnostic> diagnostics = new ArrayList<>();
        for (Selector selector : selectors) {
            Objects.requireNonNull(selector, "selector");
            NativeMethodId id = exactId(selector);
            List<ParsedMethod> matches = inputProgram.classes().stream()
                    .filter(type -> type.internalName().equals(id.owner()))
                    .flatMap(type -> type.methods().stream())
                    .filter(method -> method.name().equals(id.name())
                            && method.descriptor().equals(id.descriptor()))
                    .toList();
            if (matches.isEmpty()) {
                diagnostics.add(Diagnostic.error(
                                DiagnosticStage.PROTECTION,
                                PublicMethodInternalizationAllowListDiagnostics
                                        .TARGET_NOT_FOUND,
                                "public method internalization allowlist target is not "
                                        + "an input-base method: " + id.methodKey())
                        .at(location(id)));
                continue;
            }
            if (matches.size() != 1) {
                diagnostics.add(Diagnostic.error(
                                DiagnosticStage.PROTECTION,
                                PublicMethodInternalizationAllowListDiagnostics
                                        .TARGET_AMBIGUOUS,
                                "public method internalization allowlist target is "
                                        + "ambiguous in the input: " + id.methodKey())
                        .at(location(id)));
                continue;
            }
            ParsedMethod method = matches.get(0);
            if (!method.accessFlags().isPublic() || !method.hasCode()) {
                diagnostics.add(Diagnostic.error(
                                DiagnosticStage.PROTECTION,
                                PublicMethodInternalizationAllowListDiagnostics
                                        .TARGET_NOT_PUBLIC_CODE,
                                "public method internalization allowlist target must be "
                                        + "a public Code-bearing input-base method: "
                                        + id.methodKey())
                        .at(location(id)));
                continue;
            }
            methods.add(id);
        }
        return new Result(methods, diagnostics);
    }

    private NativeMethodId exactId(Selector selector) {
        if (!selector.isMethodSelector()
                || selector.classPattern().contains("*")) {
            throw new IllegalArgumentException(
                    "public method allowlist selector must be exact: "
                            + selector.raw());
        }
        return new NativeMethodId(
                selector.classPattern(),
                selector.methodName().orElseThrow(),
                selector.descriptor().orElseThrow());
    }

    private DiagnosticLocation location(NativeMethodId id) {
        return DiagnosticLocation.methodLocation(
                id.owner(),
                id.name(),
                id.descriptor());
    }

    public record Result(
            Set<NativeMethodId> methods,
            List<Diagnostic> diagnostics) {
        public Result {
            methods = Set.copyOf(Objects.requireNonNull(methods, "methods"));
            diagnostics = Objects.requireNonNull(diagnostics, "diagnostics")
                    .stream()
                    .sorted()
                    .toList();
        }

        public boolean successful() {
            return diagnostics.isEmpty();
        }
    }
}
