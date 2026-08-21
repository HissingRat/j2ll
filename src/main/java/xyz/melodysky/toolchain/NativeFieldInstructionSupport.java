package xyz.melodysky.toolchain;

import xyz.melodysky.analysis.field.NativeFieldStorageKind;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.NativeFieldSlotRef;

/** Structural LLVM support policy for JVM and internalized field operations. */
final class NativeFieldInstructionSupport {
    boolean supports(IrInstruction instruction) {
        if (instruction.opcode() == IrOpcode.GET_STATIC
                || instruction.opcode() == IrOpcode.GET_NATIVE_STATIC) {
            return instruction.operands().isEmpty()
                    && instruction.result().map(result -> result.type())
                            .filter(type -> instruction.opcode()
                                            == IrOpcode.GET_NATIVE_STATIC
                                    ? nativeKindMatches(instruction, type)
                                    : isSupportedType(type))
                            .isPresent();
        }
        if (instruction.opcode() == IrOpcode.PUT_STATIC
                || instruction.opcode() == IrOpcode.PUT_NATIVE_STATIC) {
            return instruction.operands().size() == 1
                    && (instruction.opcode() == IrOpcode.PUT_NATIVE_STATIC
                            ? nativeKindMatches(
                                    instruction,
                                    instruction.operands().get(0).type())
                            : isSupportedType(
                                    instruction.operands().get(0).type()));
        }
        if (instruction.opcode() == IrOpcode.GET_FIELD) {
            return instruction.operands().size() == 1
                    && instruction.operands().get(0).type()
                            == IrType.REFERENCE
                    && instruction.result().map(result -> result.type())
                            .filter(this::isSupportedType).isPresent();
        }
        if (instruction.opcode() == IrOpcode.PUT_FIELD) {
            return instruction.operands().size() == 2
                    && instruction.operands().get(0).type()
                            == IrType.REFERENCE
                    && isSupportedType(
                            instruction.operands().get(1).type());
        }
        return false;
    }

    boolean isAccess(IrOpcode opcode) {
        return opcode == IrOpcode.GET_STATIC
                || opcode == IrOpcode.PUT_STATIC
                || opcode == IrOpcode.GET_NATIVE_STATIC
                || opcode == IrOpcode.PUT_NATIVE_STATIC
                || opcode == IrOpcode.GET_FIELD
                || opcode == IrOpcode.PUT_FIELD;
    }

    private boolean isSupportedType(IrType type) {
        return type == IrType.I32
                || type == IrType.I64
                || type == IrType.F32
                || type == IrType.F64
                || type == IrType.REFERENCE;
    }

    private boolean nativeKindMatches(
            IrInstruction instruction,
            IrType type) {
        return instruction.symbol()
                .flatMap(NativeFieldSlotRef::parse)
                .map(slot -> nativeIrType(slot.kind()) == type)
                .orElse(false);
    }

    private IrType nativeIrType(NativeFieldStorageKind kind) {
        return switch (kind) {
            case BOOLEAN, BYTE, SHORT, CHAR, INT -> IrType.I32;
            case LONG -> IrType.I64;
            case FLOAT -> IrType.F32;
            case DOUBLE -> IrType.F64;
            case REFERENCE -> IrType.REFERENCE;
        };
    }
}
