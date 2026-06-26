package xyz.melodysky.packaging;

import java.util.List;
import java.util.Objects;
import xyz.melodysky.diagnostic.Diagnostic;

public record ClassRewriteResult(byte[] classBytes, List<Diagnostic> diagnostics, List<MethodRewriteDecision> applied) {
    public ClassRewriteResult {
        Objects.requireNonNull(classBytes, "classBytes");
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        applied = List.copyOf(Objects.requireNonNull(applied, "applied"));
    }
}
