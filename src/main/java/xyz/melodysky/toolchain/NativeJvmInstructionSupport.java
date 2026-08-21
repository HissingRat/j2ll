package xyz.melodysky.toolchain;

import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrType;

/** Structural support for core JVM semantics represented directly in SSA IR. */
final class NativeJvmInstructionSupport {
    boolean isArithmeticExceptionHelper(IrInstruction instruction) {
        return instruction.opcode() == IrOpcode.DIV_I32
                || instruction.opcode() == IrOpcode.REM_I32
                || instruction.opcode() == IrOpcode.DIV_I64
                || instruction.opcode() == IrOpcode.REM_I64;
    }

    boolean isNumericHelper(IrInstruction instruction) {
        return switch (instruction.opcode()) {
            case I2B, I2C, I2S, F2I, F2L, D2I, D2L,
                    LCMP, FCMPL, FCMPG, DCMPL, DCMPG -> true;
            default -> false;
        };
    }

    boolean isMemoryFence(IrInstruction instruction) {
        return switch (instruction.opcode()) {
            case VOLATILE_READ_BARRIER,
                    VOLATILE_WRITE_BARRIER,
                    FINAL_FIELD_PUBLICATION,
                    MONITOR_HAPPENS_BEFORE,
                    CLASS_INIT_HAPPENS_BEFORE,
                    CLASS_INIT_ACTIVE_USE -> true;
            default -> false;
        };
    }

    boolean isSymbolicConstant(IrInstruction instruction) {
        return instruction.opcode() == IrOpcode.CONST_STRING
                || instruction.opcode() == IrOpcode.CONST_CLASS;
    }

    boolean isClassInitHelper(IrInstruction instruction) {
        return switch (instruction.opcode()) {
            case CLASS_OBJECT,
                    CLASS_INIT_GUARD,
                    CLASS_INIT_BEGIN,
                    CLASS_INIT_END,
                    CLASS_INIT_FAILED,
                    CLASS_INIT_HAPPENS_BEFORE -> true;
            default -> false;
        };
    }

    boolean supportsClassInitHelper(IrInstruction instruction) {
        if (instruction.opcode() == IrOpcode.CLASS_OBJECT) {
            return operandsAre(instruction, IrType.I64)
                    && resultIs(instruction, IrType.REFERENCE);
        }
        if (instruction.opcode() == IrOpcode.CLASS_INIT_GUARD
                || instruction.opcode() == IrOpcode.CLASS_INIT_BEGIN
                || instruction.opcode() == IrOpcode.CLASS_INIT_END) {
            return operandsAre(instruction, IrType.REFERENCE)
                    && instruction.result().isEmpty();
        }
        if (instruction.opcode() == IrOpcode.CLASS_INIT_FAILED) {
            return operandsAre(
                            instruction,
                            IrType.REFERENCE,
                            IrType.REFERENCE)
                    && instruction.result().isEmpty();
        }
        return instruction.opcode() == IrOpcode.CLASS_INIT_HAPPENS_BEFORE
                && instruction.operands().stream()
                        .allMatch(operand ->
                                operand.type() == IrType.REFERENCE)
                && instruction.result().isEmpty();
    }

    boolean isTypeHelper(IrInstruction instruction) {
        return instruction.opcode() == IrOpcode.CHECKCAST
                || instruction.opcode() == IrOpcode.INSTANCEOF;
    }

    boolean supportsTypeHelper(IrInstruction instruction) {
        if (instruction.opcode() == IrOpcode.CHECKCAST) {
            return operandsAre(instruction, IrType.REFERENCE)
                    && resultIs(instruction, IrType.REFERENCE)
                    && instruction.symbol()
                            .map(symbol -> symbol.startsWith("checkcast:"))
                            .orElse(false);
        }
        if (instruction.opcode() == IrOpcode.INSTANCEOF) {
            return operandsAre(instruction, IrType.REFERENCE)
                    && resultIs(instruction, IrType.I32)
                    && instruction.symbol()
                            .map(symbol -> symbol.startsWith("instanceof:"))
                            .orElse(false);
        }
        return false;
    }

    boolean isMonitorHelper(IrInstruction instruction) {
        return instruction.opcode() == IrOpcode.MONITOR_ENTER
                || instruction.opcode() == IrOpcode.MONITOR_EXIT
                || instruction.opcode() == IrOpcode.MONITOR_EXIT_ON_EXCEPTION;
    }

    boolean supportsMonitorHelper(IrInstruction instruction) {
        return instruction.result().isEmpty()
                && operandsAre(instruction, IrType.REFERENCE);
    }

    boolean isThrowableSemanticUnsupportedCall(
            IrInstruction instruction) {
        if (instruction.symbol().isEmpty()) {
            return false;
        }
        String symbol = instruction.symbol().orElseThrow();
        return symbol.equals(
                        "java/lang/Throwable#getMessage!()Ljava/lang/String;")
                || symbol.equals(
                        "java/lang/Throwable#getCause!()Ljava/lang/Throwable;")
                || symbol.equals(
                        "java/lang/Throwable#initCause!(Ljava/lang/Throwable;)Ljava/lang/Throwable;");
    }

    boolean isThrowableFamilyConstructor(String symbol) {
        int separator = symbol.indexOf("#<init>!");
        if (separator < 0) {
            return false;
        }
        String owner = symbol.substring(0, separator);
        return owner.equals("java/lang/Throwable")
                || owner.endsWith("Exception")
                || owner.endsWith("Error");
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
