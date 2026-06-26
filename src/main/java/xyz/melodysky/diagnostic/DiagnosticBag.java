package xyz.melodysky.diagnostic;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class DiagnosticBag {
    private final Set<Diagnostic> diagnostics = new LinkedHashSet<>();

    public void add(Diagnostic diagnostic) {
        diagnostics.add(Objects.requireNonNull(diagnostic, "diagnostic"));
    }

    public void addAll(Iterable<Diagnostic> diagnosticsToAdd) {
        Objects.requireNonNull(diagnosticsToAdd, "diagnosticsToAdd");
        for (Diagnostic diagnostic : diagnosticsToAdd) {
            add(diagnostic);
        }
    }

    public List<Diagnostic> diagnostics() {
        ArrayList<Diagnostic> sorted = new ArrayList<>(diagnostics);
        sorted.sort(Diagnostic::compareTo);
        return List.copyOf(sorted);
    }

    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(diagnostic -> diagnostic.severity() == DiagnosticSeverity.ERROR);
    }

    public int size() {
        return diagnostics.size();
    }
}
