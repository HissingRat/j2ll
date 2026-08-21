package xyz.melodysky.toolchain;

import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.runtime.PureNativeJdkRuntimeHelpers;

/** Structural support policy for closed JDK scalar and pure-native helpers. */
final class NativeJdkInstructionSupport {
    boolean isScalarHelper(IrInstruction instruction) {
        if (instruction.opcode() != IrOpcode.CALL_RUNTIME_HELPER) {
            return false;
        }
        return instruction.symbol().map(NativeRuntimeHelperSymbol::base)
                .map(symbol -> symbol.equals("j2ll_rt_math_abs_i32")
                        || symbol.equals("j2ll_rt_math_abs_i64")
                        || symbol.equals("j2ll_rt_math_abs_f32")
                        || symbol.equals("j2ll_rt_math_abs_f64")
                        || symbol.equals("j2ll_rt_math_min_i32")
                        || symbol.equals("j2ll_rt_math_min_i64")
                        || symbol.equals("j2ll_rt_math_min_f32")
                        || symbol.equals("j2ll_rt_math_min_f64")
                        || symbol.equals("j2ll_rt_math_max_i32")
                        || symbol.equals("j2ll_rt_math_max_i64")
                        || symbol.equals("j2ll_rt_math_max_f32")
                        || symbol.equals("j2ll_rt_math_max_f64")
                        || symbol.startsWith("j2ll_rt_integer_")
                        || symbol.startsWith("j2ll_rt_long_")
                        || symbol.startsWith("j2ll_rt_boolean_")
                        || symbol.startsWith("j2ll_rt_double_")
                        || symbol.equals("j2ll_rt_object_get_class")
                        || symbol.equals("j2ll_rt_class_get_class_loader")
                        || symbol.equals("j2ll_rt_thread_sleep")
                        || symbol.startsWith("j2ll_rt_objects_"))
                .orElse(false);
    }

    boolean supportsScalarHelper(IrInstruction instruction) {
        String symbol = NativeRuntimeHelperSymbol.base(
                instruction.symbol().orElseThrow());
        if (symbol.equals("j2ll_rt_thread_sleep")) {
            return instruction.result().isEmpty()
                    && operandsAre(instruction, IrType.I64);
        }
        if (symbol.endsWith("_i32")) {
            return sameScalarShape(instruction, IrType.I32);
        }
        if (symbol.endsWith("_i64")) {
            return sameScalarShape(instruction, IrType.I64);
        }
        if (symbol.endsWith("_f32")) {
            return sameScalarShape(instruction, IrType.F32);
        }
        if (symbol.endsWith("_f64")) {
            return sameScalarShape(instruction, IrType.F64);
        }
        if (symbol.equals("j2ll_rt_integer_value_of")
                || symbol.equals("j2ll_rt_boolean_value_of")) {
            return resultIs(instruction, IrType.REFERENCE)
                    && operandsAre(instruction, IrType.I32);
        }
        if (symbol.equals("j2ll_rt_integer_int_value")
                || symbol.equals("j2ll_rt_boolean_boolean_value")) {
            return resultIs(instruction, IrType.I32)
                    && operandsAre(instruction, IrType.REFERENCE);
        }
        if (symbol.equals("j2ll_rt_long_value_of")) {
            return resultIs(instruction, IrType.REFERENCE)
                    && operandsAre(instruction, IrType.I64);
        }
        if (symbol.equals("j2ll_rt_long_long_value")) {
            return resultIs(instruction, IrType.I64)
                    && operandsAre(instruction, IrType.REFERENCE);
        }
        if (symbol.equals("j2ll_rt_double_value_of")) {
            return resultIs(instruction, IrType.REFERENCE)
                    && operandsAre(instruction, IrType.F64);
        }
        if (symbol.equals("j2ll_rt_double_double_value")) {
            return resultIs(instruction, IrType.F64)
                    && operandsAre(instruction, IrType.REFERENCE);
        }
        if (symbol.equals("j2ll_rt_object_get_class")
                || symbol.equals("j2ll_rt_class_get_class_loader")
                || symbol.equals("j2ll_rt_objects_require_non_null")) {
            return resultIs(instruction, IrType.REFERENCE)
                    && operandsAre(instruction, IrType.REFERENCE);
        }
        if (symbol.equals("j2ll_rt_objects_equals")) {
            return resultIs(instruction, IrType.I32)
                    && operandsAre(
                            instruction,
                            IrType.REFERENCE,
                            IrType.REFERENCE);
        }
        return false;
    }

    boolean isPureNativeHelper(IrInstruction instruction) {
        return instruction.opcode() == IrOpcode.CALL_RUNTIME_HELPER
                && instruction.symbol()
                        .map(PureNativeJdkRuntimeHelpers
                                ::isI32BigEndianFrameHelper)
                        .orElse(false);
    }

    boolean supportsPureNativeHelper(IrInstruction instruction) {
        String symbol = NativeRuntimeHelperSymbol.base(
                instruction.symbol().orElseThrow());
        if (symbol.equals(PureNativeJdkRuntimeHelpers
                .I32_BIG_ENDIAN_FRAME_NEW)) {
            return resultIs(instruction, IrType.REFERENCE)
                    && instruction.operands().isEmpty();
        }
        if (symbol.equals(PureNativeJdkRuntimeHelpers
                .I32_BIG_ENDIAN_FRAME_WRITE)) {
            return resultIs(instruction, IrType.REFERENCE)
                    && operandsAre(
                            instruction,
                            IrType.REFERENCE,
                            IrType.I32);
        }
        if (symbol.equals(PureNativeJdkRuntimeHelpers
                .I32_BIG_ENDIAN_FRAME_FINISH)) {
            return resultIs(instruction, IrType.REFERENCE)
                    && operandsAre(instruction, IrType.REFERENCE);
        }
        return false;
    }

    private boolean sameScalarShape(
            IrInstruction instruction,
            IrType type) {
        return resultIs(instruction, type)
                && !instruction.operands().isEmpty()
                && instruction.operands().stream()
                        .allMatch(operand -> operand.type() == type);
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
