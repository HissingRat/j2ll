package xyz.melodysky.config;

import xyz.melodysky.diagnostic.DiagnosticCode;

public final class ConfigDiagnostics {
    public static final DiagnosticCode UNKNOWN_FIELD = DiagnosticCode.of("UNKNOWN_FIELD");
    public static final DiagnosticCode MISSING_REQUIRED_FIELD = DiagnosticCode.of("MISSING_REQUIRED_FIELD");
    public static final DiagnosticCode INVALID_FIELD_VALUE = DiagnosticCode.of("INVALID_FIELD_VALUE");
    public static final DiagnosticCode INVALID_EMBEDDED_LIBRARY_DIRECTORY =
            DiagnosticCode.of("INVALID_EMBEDDED_LIBRARY_DIRECTORY");
    public static final DiagnosticCode UNSUPPORTED_SCHEMA_VERSION = DiagnosticCode.of("UNSUPPORTED_SCHEMA_VERSION");
    public static final DiagnosticCode NO_TARGET_SELECTED = DiagnosticCode.of("NO_TARGET_SELECTED");
    public static final DiagnosticCode HOST_TARGET_UNAVAILABLE = DiagnosticCode.of("HOST_TARGET_UNAVAILABLE");
    public static final DiagnosticCode INVALID_PATH = DiagnosticCode.of("INVALID_PATH");
    public static final DiagnosticCode INVALID_SELECTOR = DiagnosticCode.of("INVALID_SELECTOR");
    public static final DiagnosticCode UNMATCHED_WHITELIST_SELECTOR = DiagnosticCode.of("UNMATCHED_WHITELIST_SELECTOR");
    public static final DiagnosticCode UNMATCHED_BLACKLIST_SELECTOR = DiagnosticCode.of("UNMATCHED_BLACKLIST_SELECTOR");
    public static final DiagnosticCode FIELD_INTERNALIZATION_REQUIRES_CLOSED_WORLD =
            DiagnosticCode.of("FIELD_INTERNALIZATION_REQUIRES_CLOSED_WORLD");

    private ConfigDiagnostics() {
    }
}
