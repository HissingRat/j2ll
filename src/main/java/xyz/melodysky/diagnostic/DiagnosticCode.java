package xyz.melodysky.diagnostic;

import java.util.Objects;

public record DiagnosticCode(String value) implements Comparable<DiagnosticCode> {
    public static final DiagnosticCode BOOTSTRAP_STAGE_RAN = new DiagnosticCode("BOOTSTRAP_STAGE_RAN");
    public static final DiagnosticCode BOOTSTRAP_VALIDATION = new DiagnosticCode("BOOTSTRAP_VALIDATION");
    public static final DiagnosticCode JVM_HELPER_UNSUPPORTED = new DiagnosticCode("JVM_HELPER_UNSUPPORTED");
    public static final DiagnosticCode UNSAFE_RAW_MEMORY_UNSUPPORTED =
            new DiagnosticCode("UNSAFE_RAW_MEMORY_UNSUPPORTED");
    public static final DiagnosticCode VAR_HANDLE_DYNAMIC_UNSUPPORTED =
            new DiagnosticCode("VAR_HANDLE_DYNAMIC_UNSUPPORTED");
    public static final DiagnosticCode SIGNED_INPUT_REJECTED = new DiagnosticCode("SIGNED_INPUT_REJECTED");
    public static final DiagnosticCode SIGNATURE_STRIPPED = new DiagnosticCode("SIGNATURE_STRIPPED");
    public static final DiagnosticCode SIGNATURE_RESIGN_FAILED = new DiagnosticCode("SIGNATURE_RESIGN_FAILED");
    public static final DiagnosticCode ARTIFACT_AUDIT_FAILED = new DiagnosticCode("ARTIFACT_AUDIT_FAILED");
    public static final DiagnosticCode RELEASE_READINESS_FAILED = new DiagnosticCode("RELEASE_READINESS_FAILED");
    public static final DiagnosticCode SKIPPED_METHODS_NOT_APPROVED =
            new DiagnosticCode("SKIPPED_METHODS_NOT_APPROVED");
    public static final DiagnosticCode SKIPPED_METHOD_CONFIRMATION_INPUT_FAILED =
            new DiagnosticCode("SKIPPED_METHOD_CONFIRMATION_INPUT_FAILED");

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
