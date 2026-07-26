package xyz.melodysky.ir.pass.protection;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrTerminator;

public final class BasicBlockSplittingPass implements ProtectionPass {
    @Override
    public String name() {
        return "BASIC_BLOCK_SPLITTING";
    }

    @Override
    public boolean enabled(ProtectionConfig config) {
        return config.enabled() && config.basicBlockSplitting();
    }

    @Override
    public boolean applicable(IrMethod method) {
        if (isStubBackedMethod(method)) {
            return false;
        }
        return splitCandidate(method) != null;
    }

    @Override
    public String skipReasonCode(IrMethod method) {
        if (isStubBackedMethod(method)) {
            return "PROTECTION_STUB_BACKED_METHOD";
        }
        return "PROTECTION_CFG_SHAPE_NOT_SUPPORTED";
    }

    @Override
    public IrMethod run(IrMethod method, ProtectionConfig config) {
        if (!enabled(config) || !applicable(method)) {
            return method;
        }
        IrBlock original = splitCandidate(method);
        if (original == null) {
            return method;
        }
        ProtectionRandom random = new ProtectionRandom(config.seed());
        String token = random.token(name(), method.methodKey() + ":" + original.name(), 10);
        String suffixName = uniqueBlockName(method, "split_" + token);
        int splitIndex = splitIndex(original, token);

        IrBlock prefix = new IrBlock(
                original.name(),
                original.parameters(),
                original.exceptionCatchTypes(),
                original.exceptionEdges(),
                original.instructions().subList(0, splitIndex),
                IrTerminator.gotoBlock(suffixName));
        IrBlock suffix = new IrBlock(
                suffixName,
                List.of(),
                List.of(),
                List.of(),
                original.instructions().subList(splitIndex, original.instructions().size()),
                original.terminator());

        ArrayList<IrBlock> blocks = new ArrayList<>(method.blocks().size() + 1);
        for (IrBlock block : method.blocks()) {
            if (block == original) {
                blocks.add(prefix);
                blocks.add(suffix);
            } else {
                blocks.add(block);
            }
        }
        return new IrMethod(
                method.owner(),
                method.name(),
                method.descriptor(),
                method.returnType(),
                method.parameters(),
                blocks);
    }

    private IrBlock splitCandidate(IrMethod method) {
        for (IrBlock block : method.blocks()) {
            if (isSafeBlock(block)) {
                return block;
            }
        }
        return null;
    }

    private boolean isSafeBlock(IrBlock block) {
        if (block.instructions().size() < 2
                || !block.parameters().isEmpty()
                || block.isExceptionHandler()
                || !block.exceptionCatchTypes().isEmpty()
                || !block.exceptionEdges().isEmpty()) {
            return false;
        }
        return block.instructions().stream().allMatch(instruction -> instruction.exceptionSites().isEmpty()
                && !isSensitiveOpcode(instruction.opcode()));
    }

    private int splitIndex(IrBlock block, String token) {
        int availableBoundaries = block.instructions().size() - 1;
        long tokenValue = Long.parseUnsignedLong(token, 16);
        return 1 + (int) Long.remainderUnsigned(tokenValue, availableBoundaries);
    }

    private String uniqueBlockName(IrMethod method, String preferredName) {
        Set<String> names = new HashSet<>();
        method.blocks().stream().map(IrBlock::name).forEach(names::add);
        if (!names.contains(preferredName)) {
            return preferredName;
        }
        int suffix = 1;
        while (names.contains(preferredName + "_" + suffix)) {
            suffix++;
        }
        return preferredName + "_" + suffix;
    }

    private boolean isStubBackedMethod(IrMethod method) {
        return method.name().equals("<init>") || method.name().equals("<clinit>");
    }

    private boolean isSensitiveOpcode(IrOpcode opcode) {
        return opcode == IrOpcode.CALL_RUNTIME_HELPER
                || opcode == IrOpcode.CALL_STATIC
                || opcode == IrOpcode.CALL_SPECIAL
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
                || opcode == IrOpcode.CLASS_INIT_HAPPENS_BEFORE;
    }
}
