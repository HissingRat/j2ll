package xyz.melodysky.ir.pass.protection;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrTerminatorKind;

final class MethodInliningSafety {
    private static final Set<IrOpcode> PURE_OPCODES = EnumSet.of(
            IrOpcode.CONST_INT,
            IrOpcode.CONST_LONG,
            IrOpcode.CONST_FLOAT,
            IrOpcode.CONST_DOUBLE,
            IrOpcode.CONST_NULL,
            IrOpcode.ADD_I32,
            IrOpcode.SUB_I32,
            IrOpcode.MUL_I32,
            IrOpcode.NEG_I32,
            IrOpcode.SHL_I32,
            IrOpcode.SHR_I32,
            IrOpcode.USHR_I32,
            IrOpcode.AND_I32,
            IrOpcode.OR_I32,
            IrOpcode.XOR_I32,
            IrOpcode.BITCAST_I32_TO_F32,
            IrOpcode.CMP_EQ_I32,
            IrOpcode.CMP_NE_I32,
            IrOpcode.CMP_LT_I32,
            IrOpcode.CMP_LE_I32,
            IrOpcode.CMP_GT_I32,
            IrOpcode.CMP_GE_I32,
            IrOpcode.CMP_EQ_REF,
            IrOpcode.CMP_NE_REF,
            IrOpcode.ADD_I64,
            IrOpcode.SUB_I64,
            IrOpcode.MUL_I64,
            IrOpcode.NEG_I64,
            IrOpcode.SHL_I64,
            IrOpcode.SHR_I64,
            IrOpcode.USHR_I64,
            IrOpcode.AND_I64,
            IrOpcode.OR_I64,
            IrOpcode.XOR_I64,
            IrOpcode.BITCAST_I64_TO_F64,
            IrOpcode.ADD_F32,
            IrOpcode.SUB_F32,
            IrOpcode.MUL_F32,
            IrOpcode.DIV_F32,
            IrOpcode.REM_F32,
            IrOpcode.NEG_F32,
            IrOpcode.ADD_F64,
            IrOpcode.SUB_F64,
            IrOpcode.MUL_F64,
            IrOpcode.DIV_F64,
            IrOpcode.REM_F64,
            IrOpcode.NEG_F64,
            IrOpcode.LCMP,
            IrOpcode.FCMPL,
            IrOpcode.FCMPG,
            IrOpcode.DCMPL,
            IrOpcode.DCMPG,
            IrOpcode.I2L,
            IrOpcode.I2F,
            IrOpcode.I2D,
            IrOpcode.I2B,
            IrOpcode.I2C,
            IrOpcode.I2S,
            IrOpcode.L2I,
            IrOpcode.L2F,
            IrOpcode.L2D,
            IrOpcode.F2I,
            IrOpcode.F2L,
            IrOpcode.F2D,
            IrOpcode.D2I,
            IrOpcode.D2L,
            IrOpcode.D2F);

    Optional<String> rejectionReason(IrMethod callee, int maxInstructions) {
        if (callee.name().equals("<init>") || callee.name().equals("<clinit>")) {
            return Optional.of(MethodInliningReason.UNSUPPORTED_CALLEE_SHAPE);
        }
        int instructionCount = callee.blocks().stream()
                .mapToInt(block -> block.instructions().size())
                .sum();
        if (instructionCount > maxInstructions) {
            return Optional.of(MethodInliningReason.CALLEE_TOO_LARGE);
        }
        if (callee.blocks().isEmpty()
                || !callee.blocks().get(0).parameters().isEmpty()
                || callee.blocks().stream().noneMatch(block -> block.terminator().kind() == IrTerminatorKind.RETURN)) {
            return Optional.of(MethodInliningReason.UNSUPPORTED_CALLEE_SHAPE);
        }
        if (hasExceptionShape(callee)) {
            return Optional.of(MethodInliningReason.EXCEPTION_SENSITIVE);
        }
        if (hasMonitorOrJmmShape(callee)) {
            return Optional.of(MethodInliningReason.MONITOR_JMM_SENSITIVE);
        }
        if (hasCallOrFieldShape(callee)) {
            return Optional.of(MethodInliningReason.CALL_OR_FIELD_SENSITIVE);
        }
        boolean unsupported = callee.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(instruction -> !PURE_OPCODES.contains(instruction.opcode()));
        return unsupported
                ? Optional.of(MethodInliningReason.UNSUPPORTED_CALLEE_SHAPE)
                : Optional.empty();
    }

    private boolean hasExceptionShape(IrMethod method) {
        return method.blocks().stream().anyMatch(block -> block.isExceptionHandler()
                || !block.exceptionCatchTypes().isEmpty()
                || !block.exceptionEdges().isEmpty()
                || block.terminator().kind() == IrTerminatorKind.THROW
                || block.instructions().stream().anyMatch(instruction ->
                        !instruction.exceptionSites().isEmpty()
                                || instruction.opcode() == IrOpcode.DIV_I32
                                || instruction.opcode() == IrOpcode.REM_I32
                                || instruction.opcode() == IrOpcode.DIV_I64
                                || instruction.opcode() == IrOpcode.REM_I64));
    }

    private boolean hasMonitorOrJmmShape(IrMethod method) {
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .map(instruction -> instruction.opcode())
                .anyMatch(opcode -> opcode == IrOpcode.MONITOR_ENTER
                        || opcode == IrOpcode.MONITOR_EXIT
                        || opcode == IrOpcode.MONITOR_EXIT_ON_EXCEPTION
                        || opcode == IrOpcode.VOLATILE_READ_BARRIER
                        || opcode == IrOpcode.VOLATILE_WRITE_BARRIER
                        || opcode == IrOpcode.FINAL_FIELD_PUBLICATION
                        || opcode == IrOpcode.MONITOR_HAPPENS_BEFORE
                        || opcode == IrOpcode.THREAD_START_HAPPENS_BEFORE
                        || opcode == IrOpcode.THREAD_JOIN_HAPPENS_BEFORE
                        || opcode == IrOpcode.CLASS_INIT_HAPPENS_BEFORE
                        || opcode == IrOpcode.CLASS_INIT_ACTIVE_USE);
    }

    private boolean hasCallOrFieldShape(IrMethod method) {
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .map(instruction -> instruction.opcode())
                .anyMatch(opcode -> opcode == IrOpcode.CALL_STATIC
                        || opcode == IrOpcode.CALL_SPECIAL
                        || opcode == IrOpcode.CALL_DIRECT
                        || opcode == IrOpcode.CALL_VIRTUAL
                        || opcode == IrOpcode.CALL_INTERFACE
                        || opcode == IrOpcode.CALL_DYNAMIC
                        || opcode == IrOpcode.CALL_RUNTIME_HELPER
                        || opcode == IrOpcode.GET_STATIC
                        || opcode == IrOpcode.PUT_STATIC
                        || opcode == IrOpcode.GET_FIELD
                        || opcode == IrOpcode.PUT_FIELD);
    }
}
