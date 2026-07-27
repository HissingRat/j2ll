package xyz.melodysky.diagnostic;

import java.util.Comparator;
import java.util.Objects;

public record Diagnostic(
        DiagnosticSeverity severity,
        DiagnosticCode code,
        DiagnosticStage stage,
        DiagnosticLocation location,
        String message,
        String decision) implements Comparable<Diagnostic> {
    public Diagnostic {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(message, "message");
        if (message.isBlank()) {
            throw new IllegalArgumentException("diagnostic message must not be blank");
        }
        if (location == null) {
            location = DiagnosticLocation.none();
        }
        if (decision != null && decision.isBlank()) {
            throw new IllegalArgumentException("diagnostic decision must not be blank");
        }
    }

    public static Diagnostic info(DiagnosticStage stage, DiagnosticCode code, String message) {
        return new Diagnostic(DiagnosticSeverity.INFO, code, stage, DiagnosticLocation.none(), message, null);
    }

    public static Diagnostic warning(DiagnosticStage stage, DiagnosticCode code, String message) {
        return new Diagnostic(DiagnosticSeverity.WARNING, code, stage, DiagnosticLocation.none(), message, "warning");
    }

    public static Diagnostic error(DiagnosticStage stage, DiagnosticCode code, String message) {
        return new Diagnostic(DiagnosticSeverity.ERROR, code, stage, DiagnosticLocation.none(), message, "failed");
    }

    public Diagnostic at(DiagnosticLocation newLocation) {
        return new Diagnostic(severity, code, stage, newLocation, message, decision);
    }

    public Diagnostic withDecision(String newDecision) {
        return new Diagnostic(severity, code, stage, location, message, newDecision);
    }

    @Override
    public int compareTo(Diagnostic other) {
        return COMPARATOR.compare(this, other);
    }

    private static final Comparator<Diagnostic> COMPARATOR = Comparator
            .comparing((Diagnostic diagnostic) -> nullableString(diagnostic.location.className()))
            .thenComparing(diagnostic -> nullableString(diagnostic.location.methodName()))
            .thenComparing(diagnostic -> nullableString(diagnostic.location.descriptor()))
            .thenComparing(Diagnostic::stage)
            .thenComparing(Diagnostic::severity)
            .thenComparing(Diagnostic::code)
            .thenComparing(diagnostic -> nullableInteger(diagnostic.location.instructionOffset()))
            .thenComparing(Diagnostic::message)
            .thenComparing(diagnostic -> nullableString(diagnostic.decision));

    private static String nullableString(String value) {
        return value == null ? "" : value;
    }

    private static Integer nullableInteger(Integer value) {
        return value == null ? -1 : value;
    }
}
