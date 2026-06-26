package xyz.melodysky.config;

import java.util.List;
import java.util.Optional;
import xyz.melodysky.diagnostic.Diagnostic;

public record ConfigLoadResult(Optional<ResolvedConfig> config, List<Diagnostic> diagnostics) {
    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(diagnostic -> diagnostic.severity().wireName().equals("error"));
    }
}
