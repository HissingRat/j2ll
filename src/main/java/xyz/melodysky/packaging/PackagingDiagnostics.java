package xyz.melodysky.packaging;

import xyz.melodysky.diagnostic.DiagnosticCode;

public final class PackagingDiagnostics {
    public static final DiagnosticCode STUB_REWRITE_NOT_IMPLEMENTED =
            DiagnosticCode.of("STUB_REWRITE_NOT_IMPLEMENTED");
    public static final DiagnosticCode NATIVE_ORIGINAL_REWRITE_FAILED =
            DiagnosticCode.of("NATIVE_ORIGINAL_REWRITE_FAILED");
    public static final DiagnosticCode GENERATED_RUNTIME_LOADER_ENTRY_COLLISION =
            DiagnosticCode.of("GENERATED_RUNTIME_LOADER_ENTRY_COLLISION");
    public static final DiagnosticCode GENERATED_RUNTIME_LOADER_VERSIONED_SHADOW =
            DiagnosticCode.of("GENERATED_RUNTIME_LOADER_VERSIONED_SHADOW");

    private PackagingDiagnostics() {
    }
}
