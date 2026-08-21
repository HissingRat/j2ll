package xyz.melodysky.toolchain;

import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrType;

/** Structural support policy for String and StringBuilder runtime helpers. */
final class NativeStringInstructionSupport {
    boolean isStringHelper(IrInstruction instruction) {
        if (instruction.opcode() != IrOpcode.CALL_RUNTIME_HELPER) {
            return false;
        }
        return instruction.symbol().map(NativeRuntimeHelperSymbol::base)
                .map(symbol -> symbol.equals("j2ll_rt_string_length")
                        || symbol.equals("j2ll_rt_string_is_empty")
                        || symbol.equals("j2ll_rt_string_char_at")
                        || symbol.equals("j2ll_rt_string_equals")
                        || symbol.equals("j2ll_rt_string_starts_with")
                        || symbol.equals("j2ll_rt_string_ends_with")
                        || symbol.equals("j2ll_rt_string_substring")
                        || symbol.equals("j2ll_rt_string_substring_range")
                        || symbol.equals("j2ll_rt_string_constant"))
                .orElse(false);
    }

    boolean supportsStringHelper(IrInstruction instruction) {
        String symbol = NativeRuntimeHelperSymbol.base(
                instruction.symbol().orElseThrow());
        if (symbol.equals("j2ll_rt_string_constant")) {
            return resultIs(instruction, IrType.REFERENCE)
                    && operandsAre(instruction, IrType.I64);
        }
        if (symbol.equals("j2ll_rt_string_length")
                || symbol.equals("j2ll_rt_string_is_empty")) {
            return resultIs(instruction, IrType.I32)
                    && operandsAre(instruction, IrType.REFERENCE);
        }
        if (symbol.equals("j2ll_rt_string_char_at")) {
            return resultIs(instruction, IrType.I32)
                    && operandsAre(
                            instruction,
                            IrType.REFERENCE,
                            IrType.I32);
        }
        if (symbol.equals("j2ll_rt_string_equals")
                || symbol.equals("j2ll_rt_string_starts_with")
                || symbol.equals("j2ll_rt_string_ends_with")) {
            return resultIs(instruction, IrType.I32)
                    && operandsAre(
                            instruction,
                            IrType.REFERENCE,
                            IrType.REFERENCE);
        }
        if (symbol.equals("j2ll_rt_string_substring")) {
            return resultIs(instruction, IrType.REFERENCE)
                    && operandsAre(
                            instruction,
                            IrType.REFERENCE,
                            IrType.I32);
        }
        if (symbol.equals("j2ll_rt_string_substring_range")) {
            return resultIs(instruction, IrType.REFERENCE)
                    && operandsAre(
                            instruction,
                            IrType.REFERENCE,
                            IrType.I32,
                            IrType.I32);
        }
        return false;
    }

    boolean isStringBuilderHelper(IrInstruction instruction) {
        return instruction.opcode() == IrOpcode.CALL_RUNTIME_HELPER
                && instruction.symbol().map(NativeRuntimeHelperSymbol::base)
                        .map(symbol -> symbol.startsWith(
                                "j2ll_rt_string_builder_"))
                        .orElse(false);
    }

    boolean supportsStringBuilderHelper(IrInstruction instruction) {
        String symbol = NativeRuntimeHelperSymbol.base(
                instruction.symbol().orElseThrow());
        if (symbol.equals("j2ll_rt_string_builder_new")) {
            return resultIs(instruction, IrType.REFERENCE)
                    && instruction.operands().isEmpty();
        }
        if (symbol.equals("j2ll_rt_string_builder_init")) {
            return instruction.result().isEmpty()
                    && operandsAre(instruction, IrType.REFERENCE);
        }
        if (symbol.equals("j2ll_rt_string_builder_to_string")) {
            return resultIs(instruction, IrType.REFERENCE)
                    && operandsAre(instruction, IrType.REFERENCE);
        }
        if (symbol.equals("j2ll_rt_string_builder_append_ref")) {
            return appendShape(instruction, IrType.REFERENCE);
        }
        if (symbol.equals("j2ll_rt_string_builder_append_i32")) {
            return appendShape(instruction, IrType.I32);
        }
        if (symbol.equals("j2ll_rt_string_builder_append_i64")) {
            return appendShape(instruction, IrType.I64);
        }
        if (symbol.equals("j2ll_rt_string_builder_append_f32")) {
            return appendShape(instruction, IrType.F32);
        }
        if (symbol.equals("j2ll_rt_string_builder_append_f64")) {
            return appendShape(instruction, IrType.F64);
        }
        return false;
    }

    private boolean appendShape(
            IrInstruction instruction,
            IrType valueType) {
        return resultIs(instruction, IrType.REFERENCE)
                && operandsAre(
                        instruction,
                        IrType.REFERENCE,
                        valueType);
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
