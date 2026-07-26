package xyz.melodysky.ir.pass.protection;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.ir.model.IrMethod;

public record NativeFieldIrRewriteResult(
        Map<String, IrMethod> methods,
        List<String> affectedMethods,
        List<String> affectedSlots,
        List<Diagnostic> diagnostics) {
    public NativeFieldIrRewriteResult {
        methods = Map.copyOf(Objects.requireNonNull(methods, "methods"));
        affectedMethods = affectedMethods.stream().sorted().distinct().toList();
        affectedSlots = affectedSlots.stream().sorted().distinct().toList();
        diagnostics = List.copyOf(diagnostics);
    }

    public boolean changed() {
        return !affectedMethods.isEmpty();
    }
}
