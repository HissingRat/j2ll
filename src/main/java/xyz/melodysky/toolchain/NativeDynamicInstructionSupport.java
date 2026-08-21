package xyz.melodysky.toolchain;

import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrType;

/** Structural support policy for Unsafe, VarHandle and lambda runtime helpers. */
final class NativeDynamicInstructionSupport {
    boolean isUnsafeHelper(IrInstruction instruction) {
        return hasBasePrefix(instruction, "j2ll_rt_unsafe_");
    }

    boolean isVarHandleHelper(IrInstruction instruction) {
        return hasBasePrefix(instruction, "j2ll_rt_var_handle_");
    }

    boolean isLambdaHelper(IrInstruction instruction) {
        return instruction.opcode() == IrOpcode.CALL_RUNTIME_HELPER
                && instruction.symbol()
                        .map(symbol -> NativeRuntimeHelperSymbol.base(symbol)
                                        .equals("j2ll_rt_lambda_new")
                                && symbol.contains("|lambda:"))
                        .orElse(false);
    }

    boolean supportsLambdaHelper(IrInstruction instruction) {
        return resultIs(instruction, IrType.REFERENCE)
                && operandsAre(
                        instruction,
                        IrType.I64,
                        IrType.REFERENCE);
    }

    boolean supportsVarHandleHelper(IrInstruction instruction) {
        String symbol = NativeRuntimeHelperSymbol.base(
                instruction.symbol().orElseThrow());
        if (symbol.equals("j2ll_rt_var_handle_get_int")
                || symbol.equals("j2ll_rt_var_handle_get_volatile_int")) {
            return resultIs(instruction, IrType.I32)
                    && operandsAre(
                            instruction,
                            IrType.REFERENCE,
                            IrType.REFERENCE);
        }
        if (symbol.equals("j2ll_rt_var_handle_set_int")
                || symbol.equals("j2ll_rt_var_handle_set_volatile_int")) {
            return instruction.result().isEmpty()
                    && operandsAre(
                            instruction,
                            IrType.REFERENCE,
                            IrType.REFERENCE,
                            IrType.I32);
        }
        if (symbol.equals("j2ll_rt_var_handle_compare_and_set_int")) {
            return resultIs(instruction, IrType.I32)
                    && operandsAre(
                            instruction,
                            IrType.REFERENCE,
                            IrType.REFERENCE,
                            IrType.I32,
                            IrType.I32);
        }
        return false;
    }

    boolean supportsUnsafeHelper(IrInstruction instruction) {
        String symbol = NativeRuntimeHelperSymbol.base(
                instruction.symbol().orElseThrow());
        if (symbol.equals("j2ll_rt_unsafe_object_field_offset")) {
            return resultIs(instruction, IrType.I64)
                    && operandsAre(instruction, IrType.REFERENCE);
        }
        if (symbol.equals("j2ll_rt_unsafe_get_int")) {
            return resultIs(instruction, IrType.I32)
                    && operandsAre(
                            instruction,
                            IrType.REFERENCE,
                            IrType.I64);
        }
        if (symbol.equals("j2ll_rt_unsafe_get")
                || symbol.equals("j2ll_rt_unsafe_get_volatile")) {
            return resultIs(instruction, IrType.REFERENCE)
                    && operandsAre(
                            instruction,
                            IrType.REFERENCE,
                            IrType.REFERENCE);
        }
        if (symbol.equals("j2ll_rt_unsafe_put_int")) {
            return instruction.result().isEmpty()
                    && operandsAre(
                            instruction,
                            IrType.REFERENCE,
                            IrType.I64,
                            IrType.I32);
        }
        if (symbol.equals("j2ll_rt_unsafe_compare_and_swap_int")) {
            return resultIs(instruction, IrType.I32)
                    && operandsAre(
                            instruction,
                            IrType.REFERENCE,
                            IrType.I64,
                            IrType.I32,
                            IrType.I32);
        }
        if (symbol.equals("j2ll_rt_unsafe_allocate_instance")) {
            return resultIs(instruction, IrType.REFERENCE)
                    && operandsAre(instruction, IrType.REFERENCE);
        }
        return false;
    }

    private boolean hasBasePrefix(
            IrInstruction instruction,
            String prefix) {
        return instruction.opcode() == IrOpcode.CALL_RUNTIME_HELPER
                && instruction.symbol().map(NativeRuntimeHelperSymbol::base)
                        .map(symbol -> symbol.startsWith(prefix))
                        .orElse(false);
    }

    private boolean resultIs(
            IrInstruction instruction,
            IrType type) {
        return instruction.result().map(result -> result.type())
                .filter(type::equals).isPresent();
    }

    private boolean operandsAre(
            IrInstruction instruction,
            IrType... types) {
        if (instruction.operands().size() != types.length) {
            return false;
        }
        for (int index = 0; index < types.length; index++) {
            if (instruction.operands().get(index).type() != types[index]) {
                return false;
            }
        }
        return true;
    }
}
