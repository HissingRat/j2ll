package xyz.melodysky.frontend.classfile;

import xyz.melodysky.diagnostic.DiagnosticCode;

public final class ClassParseDiagnostics {
    public static final DiagnosticCode CLASS_SOURCE_READ_FAILED = DiagnosticCode.of("CLASS_SOURCE_READ_FAILED");
    public static final DiagnosticCode CLASS_PARSE_FAILED = DiagnosticCode.of("CLASS_PARSE_FAILED");
    public static final DiagnosticCode DUPLICATE_CLASS = DiagnosticCode.of("DUPLICATE_CLASS");
    public static final DiagnosticCode DUPLICATE_METHOD = DiagnosticCode.of("DUPLICATE_METHOD");

    private ClassParseDiagnostics() {
    }
}
