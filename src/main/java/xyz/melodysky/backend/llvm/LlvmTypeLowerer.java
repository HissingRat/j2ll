package xyz.melodysky.backend.llvm;

import xyz.melodysky.backend.llvm.model.LlvmType;
import xyz.melodysky.ir.model.IrType;

public final class LlvmTypeLowerer {
    public LlvmType lower(IrType type) {
        return switch (type) {
            case VOID -> LlvmType.VOID;
            case I1 -> LlvmType.I1;
            case I32 -> LlvmType.I32;
            case I64 -> LlvmType.I64;
            case F32 -> LlvmType.F32;
            case F64 -> LlvmType.F64;
            case REFERENCE -> LlvmType.PTR;
        };
    }
}
