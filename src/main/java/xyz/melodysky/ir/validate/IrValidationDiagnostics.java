package xyz.melodysky.ir.validate;

import xyz.melodysky.diagnostic.DiagnosticCode;

public final class IrValidationDiagnostics {
    public static final DiagnosticCode IR_METHOD_HAS_NO_BLOCKS = DiagnosticCode.of("IR_METHOD_HAS_NO_BLOCKS");
    public static final DiagnosticCode IR_USE_BEFORE_DEF = DiagnosticCode.of("IR_USE_BEFORE_DEF");
    public static final DiagnosticCode IR_RETURN_TYPE_MISMATCH = DiagnosticCode.of("IR_RETURN_TYPE_MISMATCH");
    public static final DiagnosticCode IR_BLOCK_ARGUMENT_MISMATCH = DiagnosticCode.of("IR_BLOCK_ARGUMENT_MISMATCH");
    public static final DiagnosticCode IR_THROW_TYPE_MISMATCH = DiagnosticCode.of("IR_THROW_TYPE_MISMATCH");
    public static final DiagnosticCode IR_EXCEPTION_EDGE_MISMATCH = DiagnosticCode.of("IR_EXCEPTION_EDGE_MISMATCH");
    public static final DiagnosticCode IR_MONITOR_TYPE_MISMATCH = DiagnosticCode.of("IR_MONITOR_TYPE_MISMATCH");
    public static final DiagnosticCode IR_CLASS_INIT_TYPE_MISMATCH = DiagnosticCode.of("IR_CLASS_INIT_TYPE_MISMATCH");

    private IrValidationDiagnostics() {
    }
}
