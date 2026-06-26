package xyz.melodysky.runtime.metadata;

import xyz.melodysky.diagnostic.DiagnosticCode;

public final class RuntimeMetadataDiagnostics {
    public static final DiagnosticCode DUPLICATE_CLASS_METADATA = DiagnosticCode.of("DUPLICATE_CLASS_METADATA");
    public static final DiagnosticCode DUPLICATE_METHOD_METADATA = DiagnosticCode.of("DUPLICATE_METHOD_METADATA");
    public static final DiagnosticCode DUPLICATE_FIELD_METADATA = DiagnosticCode.of("DUPLICATE_FIELD_METADATA");
    public static final DiagnosticCode INVALID_CLASS_INIT_METADATA = DiagnosticCode.of("INVALID_CLASS_INIT_METADATA");

    private RuntimeMetadataDiagnostics() {
    }
}
