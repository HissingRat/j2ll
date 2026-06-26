package xyz.melodysky.ir.pass.protection;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;

public final class StringEncryptionPass implements ProtectionPass {
    private static final String PREFIX = "j2ll_rt_string_constant|string:";

    @Override
    public String name() {
        return "STRING_ENCRYPTION";
    }

    @Override
    public boolean enabled(ProtectionConfig config) {
        return config.enabled() && config.stringEncryption();
    }

    @Override
    public boolean applicable(IrMethod method) {
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .flatMap(instruction -> instruction.symbol().stream())
                .anyMatch(symbol -> symbol.startsWith(PREFIX));
    }

    @Override
    public String skipReasonCode(IrMethod method) {
        return "NO_STRING_CONSTANT_CARRIER";
    }

    @Override
    public IrMethod run(IrMethod method, ProtectionConfig config) {
        if (!enabled(config)) {
            return method;
        }
        ProtectionRandom random = new ProtectionRandom(config.seed());
        ArrayList<IrBlock> blocks = new ArrayList<>();
        for (IrBlock block : method.blocks()) {
            ArrayList<IrInstruction> instructions = new ArrayList<>();
            for (IrInstruction instruction : block.instructions()) {
                instructions.add(encryptInstruction(method, random, instruction));
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

    private IrInstruction encryptInstruction(IrMethod method, ProtectionRandom random, IrInstruction instruction) {
        if (instruction.opcode() != IrOpcode.CALL_RUNTIME_HELPER
                || instruction.symbol().isEmpty()
                || !instruction.symbol().orElseThrow().startsWith(PREFIX)) {
            return instruction;
        }
        String value = instruction.symbol().orElseThrow().substring(PREFIX.length());
        long token = Integer.toUnsignedLong(("string:" + value).hashCode());
        byte[] plain = value.getBytes(StandardCharsets.UTF_8);
        byte[] key = HexFormat.of().parseHex(random.token(name(), method.methodKey() + ":" + token, 32));
        byte[] cipher = new byte[plain.length];
        for (int index = 0; index < plain.length; index++) {
            cipher[index] = (byte) (plain[index] ^ key[index % key.length]);
        }
        String symbol = "j2ll_rt_string_constant|enc:v1:"
                + token
                + ":"
                + HexFormat.of().formatHex(key)
                + ":"
                + HexFormat.of().formatHex(cipher);
        return new IrInstruction(
                instruction.result(),
                instruction.opcode(),
                instruction.operands(),
                instruction.intLiteral(),
                instruction.longLiteral(),
                instruction.floatLiteral(),
                instruction.doubleLiteral(),
                java.util.Optional.of(symbol),
                instruction.exceptionSites());
    }
}
