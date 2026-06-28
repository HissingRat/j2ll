package xyz.melodysky.ir.ssa;

import xyz.melodysky.diagnostic.DiagnosticCode;

public final class LoweringDiagnostics {
    public static final DiagnosticCode UNSUPPORTED_OPCODE = DiagnosticCode.of("UNSUPPORTED_OPCODE");
    public static final DiagnosticCode UNSUPPORTED_CFG_SHAPE = DiagnosticCode.of("UNSUPPORTED_CFG_SHAPE");
    public static final DiagnosticCode UNSUPPORTED_COMPLEX_EXCEPTION_SHAPE = DiagnosticCode.of("UNSUPPORTED_COMPLEX_EXCEPTION_SHAPE");
    public static final DiagnosticCode UNSUPPORTED_FINALLY_SUBROUTINE = DiagnosticCode.of("UNSUPPORTED_FINALLY_SUBROUTINE");
    public static final DiagnosticCode UNSUPPORTED_MULTI_EXIT_FINALLY = DiagnosticCode.of("UNSUPPORTED_MULTI_EXIT_FINALLY");
    public static final DiagnosticCode UNSUPPORTED_EXCEPTION_STATE_MERGE = DiagnosticCode.of("UNSUPPORTED_EXCEPTION_STATE_MERGE");
    public static final DiagnosticCode UNSUPPORTED_MONITOR_FINALLY_INTERACTION =
            DiagnosticCode.of("UNSUPPORTED_MONITOR_FINALLY_INTERACTION");
    public static final DiagnosticCode UNSUPPORTED_DEFAULT_INTERFACE_SUPER =
            DiagnosticCode.of("UNSUPPORTED_DEFAULT_INTERFACE_SUPER");
    public static final DiagnosticCode UNSUPPORTED_NESTED_FINALLY = DiagnosticCode.of("UNSUPPORTED_NESTED_FINALLY");
    public static final DiagnosticCode UNSUPPORTED_SYNCHRONIZED_METHOD = DiagnosticCode.of("UNSUPPORTED_SYNCHRONIZED_METHOD");
    public static final DiagnosticCode UNSUPPORTED_SYNCHRONIZED_EXCEPTION_CLEANUP =
            DiagnosticCode.of("UNSUPPORTED_SYNCHRONIZED_EXCEPTION_CLEANUP");
    public static final DiagnosticCode UNSUPPORTED_SSA_MERGE = DiagnosticCode.of("UNSUPPORTED_SSA_MERGE");
    public static final DiagnosticCode SSA_MERGE_STACK_HEIGHT_MISMATCH = DiagnosticCode.of("SSA_MERGE_STACK_HEIGHT_MISMATCH");
    public static final DiagnosticCode SSA_MERGE_TYPE_MISMATCH = DiagnosticCode.of("SSA_MERGE_TYPE_MISMATCH");
    public static final DiagnosticCode SSA_MERGE_LOCAL_SLOT_MISMATCH = DiagnosticCode.of("SSA_MERGE_LOCAL_SLOT_MISMATCH");
    public static final DiagnosticCode STACK_UNDERFLOW = DiagnosticCode.of("STACK_UNDERFLOW");

    private LoweringDiagnostics() {
    }
}
