package xyz.melodysky.pipeline;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.report.ProtectionPassReport;

/**
 * Program-level protection output keeps compiler-internal helpers separate
 * from the Java-visible method model consumed by packaging.
 */
public record ProgramIrProtectionResult(
        Map<String, IrMethod> javaMethods,
        Map<String, IrMethod> compilerInternalMethods,
        List<ProtectionPassReport> reports,
        List<Diagnostic> diagnostics) {
    public ProgramIrProtectionResult {
        javaMethods = java.util.Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(javaMethods, "javaMethods")));
        compilerInternalMethods = java.util.Collections.unmodifiableMap(
                new LinkedHashMap<>(
                        Objects.requireNonNull(compilerInternalMethods, "compilerInternalMethods")));
        reports = List.copyOf(Objects.requireNonNull(reports, "reports"));
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }

    public Map<String, IrMethod> allNativeMethods() {
        LinkedHashMap<String, IrMethod> result = new LinkedHashMap<>();
        javaMethods.values().stream()
                .sorted(java.util.Comparator.comparing(IrMethod::methodKey))
                .forEach(method -> result.put(method.methodKey(), method));
        compilerInternalMethods.values().stream()
                .sorted(java.util.Comparator.comparing(IrMethod::methodKey))
                .forEach(method -> {
                    if (result.putIfAbsent(method.methodKey(), method) != null) {
                        throw new IllegalStateException(
                                "compiler-internal method collides with Java method " + method.methodKey());
                    }
                });
        return java.util.Collections.unmodifiableMap(result);
    }
}
