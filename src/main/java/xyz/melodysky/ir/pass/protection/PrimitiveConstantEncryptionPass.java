package xyz.melodysky.ir.pass.protection;

import java.util.ArrayList;
import java.util.List;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrTerminatorKind;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;

public final class PrimitiveConstantEncryptionPass implements ProtectionPass {
    @Override
    public String name() {
        return "CONSTANT_ENCRYPTION";
    }

    @Override
    public boolean enabled(ProtectionConfig config) {
        return config.enabled() && config.constantEncryption();
    }

    @Override
    public boolean applicable(IrMethod method) {
        if (isStubBackedMethod(method)) {
            return false;
        }
        return isSafeMethod(method) && method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(instruction -> instruction.opcode() == IrOpcode.CONST_INT
                        || instruction.opcode() == IrOpcode.CONST_LONG
                        || instruction.opcode() == IrOpcode.CONST_FLOAT
                        || instruction.opcode() == IrOpcode.CONST_DOUBLE);
    }

    @Override
    public String skipReasonCode(IrMethod method) {
        if (isStubBackedMethod(method)) {
            return "PROTECTION_STUB_BACKED_METHOD";
        }
        return isSafeMethod(method) ? "NO_PRIMITIVE_CONSTANTS" : "PROTECTION_SEMANTICALLY_SENSITIVE_METHOD";
    }

    @Override
    public IrMethod run(IrMethod method, ProtectionConfig config) {
        if (!enabled(config)) {
            return method;
        }
        ProtectionRandom random = new ProtectionRandom(config.seed());
        ArrayList<IrBlock> blocks = new ArrayList<>();
        int[] counter = new int[] {0};
        for (IrBlock block : method.blocks()) {
            ArrayList<IrInstruction> instructions = new ArrayList<>();
            for (IrInstruction instruction : block.instructions()) {
                if (instruction.opcode() == IrOpcode.CONST_INT) {
                    appendEncryptedInt(method, random, counter, instructions, instruction);
                } else if (instruction.opcode() == IrOpcode.CONST_LONG) {
                    appendEncryptedLong(method, random, counter, instructions, instruction);
                } else if (instruction.opcode() == IrOpcode.CONST_FLOAT) {
                    appendEncryptedFloat(method, random, counter, instructions, instruction);
                } else if (instruction.opcode() == IrOpcode.CONST_DOUBLE) {
                    appendEncryptedDouble(method, random, counter, instructions, instruction);
                } else {
                    instructions.add(instruction);
                }
            }
            blocks.add(new IrBlock(
                    block.name(),
                    block.parameters(),
                    block.exceptionCatchTypes(),
                    block.exceptionEdges(),
                    instructions,
                    block.terminator()));
        }
        return new IrMethod(method.owner(), method.name(), method.descriptor(), method.returnType(), method.parameters(), blocks);
    }

    private void appendEncryptedInt(
            IrMethod method,
            ProtectionRandom random,
            int[] counter,
            List<IrInstruction> instructions,
            IrInstruction instruction) {
        int value = instruction.intLiteral().orElseThrow();
        int key = (int) Long.parseUnsignedLong(
                random.token(name(), method.methodKey() + ":i32:" + counter[0] + ":" + value, 8),
                16);
        int encoded = value ^ key;
        IrValue left = new IrValue("%j2ll_ce_" + counter[0]++ + "_a", IrType.I32);
        IrValue right = new IrValue("%j2ll_ce_" + counter[0]++ + "_b", IrType.I32);
        instructions.add(IrInstruction.constInt(left, encoded));
        instructions.add(IrInstruction.constInt(right, key));
        instructions.add(IrInstruction.binary(instruction.result().orElseThrow(), IrOpcode.XOR_I32, left, right));
    }

    private void appendEncryptedLong(
            IrMethod method,
            ProtectionRandom random,
            int[] counter,
            List<IrInstruction> instructions,
            IrInstruction instruction) {
        long value = instruction.longLiteral().orElseThrow();
        long key = Long.parseUnsignedLong(
                random.token(name(), method.methodKey() + ":i64:" + counter[0] + ":" + value, 16),
                16);
        long encoded = value ^ key;
        IrValue left = new IrValue("%j2ll_ce_" + counter[0]++ + "_a", IrType.I64);
        IrValue right = new IrValue("%j2ll_ce_" + counter[0]++ + "_b", IrType.I64);
        instructions.add(IrInstruction.constLong(left, encoded));
        instructions.add(IrInstruction.constLong(right, key));
        instructions.add(IrInstruction.binary(instruction.result().orElseThrow(), IrOpcode.XOR_I64, left, right));
    }

    private void appendEncryptedFloat(
            IrMethod method,
            ProtectionRandom random,
            int[] counter,
            List<IrInstruction> instructions,
            IrInstruction instruction) {
        int bits = Float.floatToRawIntBits(instruction.floatLiteral().orElseThrow());
        int key = (int) Long.parseUnsignedLong(
                random.token(name(), method.methodKey() + ":f32:" + counter[0] + ":" + Integer.toUnsignedString(bits), 8),
                16);
        int encoded = bits ^ key;
        IrValue left = new IrValue("%j2ll_ce_" + counter[0]++ + "_a", IrType.I32);
        IrValue right = new IrValue("%j2ll_ce_" + counter[0]++ + "_b", IrType.I32);
        IrValue decoded = new IrValue("%j2ll_ce_" + counter[0]++ + "_bits", IrType.I32);
        instructions.add(IrInstruction.constInt(left, encoded));
        instructions.add(IrInstruction.constInt(right, key));
        instructions.add(IrInstruction.binary(decoded, IrOpcode.XOR_I32, left, right));
        instructions.add(IrInstruction.unary(instruction.result().orElseThrow(), IrOpcode.BITCAST_I32_TO_F32, decoded));
    }

    private void appendEncryptedDouble(
            IrMethod method,
            ProtectionRandom random,
            int[] counter,
            List<IrInstruction> instructions,
            IrInstruction instruction) {
        long bits = Double.doubleToRawLongBits(instruction.doubleLiteral().orElseThrow());
        long key = Long.parseUnsignedLong(
                random.token(name(), method.methodKey() + ":f64:" + counter[0] + ":" + Long.toUnsignedString(bits), 16),
                16);
        long encoded = bits ^ key;
        IrValue left = new IrValue("%j2ll_ce_" + counter[0]++ + "_a", IrType.I64);
        IrValue right = new IrValue("%j2ll_ce_" + counter[0]++ + "_b", IrType.I64);
        IrValue decoded = new IrValue("%j2ll_ce_" + counter[0]++ + "_bits", IrType.I64);
        instructions.add(IrInstruction.constLong(left, encoded));
        instructions.add(IrInstruction.constLong(right, key));
        instructions.add(IrInstruction.binary(decoded, IrOpcode.XOR_I64, left, right));
        instructions.add(IrInstruction.unary(instruction.result().orElseThrow(), IrOpcode.BITCAST_I64_TO_F64, decoded));
    }

    private boolean isSafeMethod(IrMethod method) {
        if (method.blocks().stream().anyMatch(block -> block.isExceptionHandler()
                || !block.exceptionEdges().isEmpty()
                || block.terminator().kind() == IrTerminatorKind.THROW
                || block.terminator().kind() == IrTerminatorKind.SWITCH)) {
            return false;
        }
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .allMatch(instruction -> instruction.exceptionSites().isEmpty()
                        && !isSensitiveOpcode(instruction.opcode()));
    }

    private boolean isStubBackedMethod(IrMethod method) {
        return method.name().equals("<init>") || method.name().equals("<clinit>");
    }

    private boolean isSensitiveOpcode(IrOpcode opcode) {
        return opcode == IrOpcode.CALL_RUNTIME_HELPER
                || opcode == IrOpcode.CALL_STATIC
                || opcode == IrOpcode.CALL_SPECIAL
                || opcode == IrOpcode.CALL_DIRECT
                || opcode == IrOpcode.CALL_VIRTUAL
                || opcode == IrOpcode.CALL_INTERFACE
                || opcode == IrOpcode.CALL_DYNAMIC
                || opcode == IrOpcode.GET_STATIC
                || opcode == IrOpcode.PUT_STATIC
                || opcode == IrOpcode.GET_FIELD
                || opcode == IrOpcode.PUT_FIELD
                || opcode == IrOpcode.MONITOR_ENTER
                || opcode == IrOpcode.MONITOR_EXIT
                || opcode == IrOpcode.MONITOR_EXIT_ON_EXCEPTION
                || opcode == IrOpcode.VOLATILE_READ_BARRIER
                || opcode == IrOpcode.VOLATILE_WRITE_BARRIER
                || opcode == IrOpcode.FINAL_FIELD_PUBLICATION
                || opcode == IrOpcode.MONITOR_HAPPENS_BEFORE
                || opcode == IrOpcode.CLASS_INIT_HAPPENS_BEFORE
                || opcode == IrOpcode.CLASS_INIT_ACTIVE_USE;
    }
}
