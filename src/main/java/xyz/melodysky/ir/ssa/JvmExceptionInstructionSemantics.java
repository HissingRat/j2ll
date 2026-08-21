package xyz.melodysky.ir.ssa;

import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrOpcode;

/**
 * Classifies IR operations whose JVM/JNI implementation may leave a pending
 * exception.
 */
public final class JvmExceptionInstructionSemantics {
    public boolean canRaiseJvmException(IrInstruction instruction) {
        IrOpcode opcode = instruction.opcode();
        return switch (opcode) {
            case CONST_STRING, CONST_CLASS, CONST_METHOD_TYPE, CONST_METHOD_HANDLE,
                    CLASS_OBJECT, CLASS_INIT_GUARD, CLASS_INIT_BEGIN, CLASS_INIT_END, CLASS_INIT_FAILED,
                    DIV_I32, REM_I32, DIV_I64, REM_I64,
                    NEW_OBJECT, NEW_ARRAY, NEW_MULTI_ARRAY,
                    ARRAY_LENGTH, ARRAY_LOAD_I32, ARRAY_LOAD_I64, ARRAY_LOAD_F32, ARRAY_LOAD_F64,
                    ARRAY_LOAD_REF, ARRAY_STORE_I32, ARRAY_STORE_I64, ARRAY_STORE_F32,
                    ARRAY_STORE_F64, ARRAY_STORE_REF,
                    CHECKCAST, INSTANCEOF,
                    GET_STATIC, PUT_STATIC, GET_NATIVE_STATIC, PUT_NATIVE_STATIC, GET_FIELD, PUT_FIELD,
                    CALL_STATIC, CALL_SPECIAL, CALL_DIRECT, CALL_VIRTUAL, CALL_INTERFACE, CALL_DYNAMIC,
                    CALL_RUNTIME_HELPER,
                    MONITOR_ENTER, MONITOR_EXIT, MONITOR_EXIT_ON_EXCEPTION -> true;
            default -> false;
        };
    }
}
