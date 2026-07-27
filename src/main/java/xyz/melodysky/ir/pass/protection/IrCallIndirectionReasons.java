package xyz.melodysky.ir.pass.protection;

public final class IrCallIndirectionReasons {
    public static final String DISABLED = "IR_CALL_INDIRECTION_DISABLED";
    public static final String TABLE = "IR_CALL_INDIRECTION_TABLE";
    public static final String DISPATCHER = "IR_CALL_INDIRECTION_DISPATCHER";
    public static final String NO_CANDIDATE = "IR_CALL_INDIRECTION_NO_CANDIDATE";
    public static final String ALREADY_PLANNED = "IR_CALL_INDIRECTION_ALREADY_PLANNED";
    public static final String SEMANTIC_TARGET_MISSING =
            "IR_CALL_INDIRECTION_SEMANTIC_TARGET_MISSING";
    public static final String DYNAMIC_OR_HELPER_SENSITIVE =
            "IR_CALL_INDIRECTION_DYNAMIC_OR_HELPER_SENSITIVE";
    public static final String UNRESOLVED_TARGET = "IR_CALL_INDIRECTION_UNRESOLVED_TARGET";
    public static final String MULTIPLE_TARGETS = "IR_CALL_INDIRECTION_MULTIPLE_TARGETS";
    public static final String FACT_KIND_MISMATCH = "IR_CALL_INDIRECTION_FACT_KIND_MISMATCH";
    public static final String FACT_TARGET_MISMATCH = "IR_CALL_INDIRECTION_FACT_TARGET_MISMATCH";
    public static final String NATIVE_TARGET_UNAVAILABLE =
            "IR_CALL_INDIRECTION_NATIVE_TARGET_UNAVAILABLE";
    public static final String CALLER_NOT_NATIVE_LOWERED =
            "IR_CALL_INDIRECTION_CALLER_NOT_NATIVE_LOWERED";
    public static final String TARGET_NOT_NATIVE_LOWERED =
            "IR_CALL_INDIRECTION_TARGET_NOT_NATIVE_LOWERED";
    public static final String TARGET_NOT_IN_PROGRAM = "IR_CALL_INDIRECTION_TARGET_NOT_IN_PROGRAM";
    public static final String CONSTRUCTOR_OR_INITIALIZER =
            "IR_CALL_INDIRECTION_CONSTRUCTOR_OR_INITIALIZER";
    public static final String SIGNATURE_MISMATCH = "IR_CALL_INDIRECTION_SIGNATURE_MISMATCH";
    public static final String BACKEND_UNSUPPORTED_SHAPE =
            "IR_CALL_INDIRECTION_BACKEND_UNSUPPORTED_SHAPE";
    public static final String CLASS_INIT_GUARD_MISSING =
            "IR_CALL_INDIRECTION_CLASS_INIT_GUARD_MISSING";
    public static final String VALIDATION_FAILED = "IR_CALL_INDIRECTION_VALIDATION_FAILED";

    private IrCallIndirectionReasons() {
    }
}
