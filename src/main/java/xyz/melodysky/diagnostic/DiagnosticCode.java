package xyz.melodysky.diagnostic;

import java.util.Objects;

public record DiagnosticCode(String value) implements Comparable<DiagnosticCode> {
    public static final DiagnosticCode BOOTSTRAP_STAGE_RAN = new DiagnosticCode("BOOTSTRAP_STAGE_RAN");
    public static final DiagnosticCode BOOTSTRAP_VALIDATION = new DiagnosticCode("BOOTSTRAP_VALIDATION");
    public static final DiagnosticCode JVM_HELPER_FALLBACK = new DiagnosticCode("JVM_HELPER_FALLBACK");

    public DiagnosticCode {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("diagnostic code must not be blank");
        }
    }

    public static DiagnosticCode of(String value) {
        return new DiagnosticCode(value);
    }

    @Override
    public int compareTo(DiagnosticCode other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
