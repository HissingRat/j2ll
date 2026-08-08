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
        SplitCandidate candidate = splitCandidate(method);
        if (candidate == null) {
            return method;
        }
        IrBlock original = candidate.block();
        ProtectionRandom random = new ProtectionRandom(config.seed());
        String token = random.token(name(), method.methodKey() + ":" + original.name(), 10);
        String suffixName = uniqueBlockName(method, "split_" + token);
        int splitIndex = splitIndex(candidate, token);

        IrBlock prefix = new IrBlock(
                original.name(),
                original.parameters(),
                original.exceptionCatchTypes(),
                List.of(),
                original.instructions().subList(0, splitIndex),
                IrTerminator.gotoBlock(suffixName));
        IrBlock suffix = new IrBlock(
                suffixName,
                List.of(),
                List.of(),
                original.exceptionEdges(),
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

    private SplitCandidate splitCandidate(IrMethod method) {
        for (IrBlock block : method.blocks()) {
            List<Integer> boundaries = safeSplitBoundaries(block);
            if (!boundaries.isEmpty()) {
                return new SplitCandidate(block, boundaries);
            }
        }
        return null;
    }

    private List<Integer> safeSplitBoundaries(IrBlock block) {
        if (block.instructions().size() < 2
                || block.isExceptionHandler()
                || block.instructions().stream()
                        .map(IrInstruction::opcode)
                        .anyMatch(this::isMonitorOrJmmSensitiveOpcode)) {
            return List.of();
        }
        ArrayList<Integer> boundaries = new ArrayList<>();
        for (int index = 1; index < block.instructions().size(); index++) {
            IrOpcode before = block.instructions().get(index - 1).opcode();
            IrOpcode after = block.instructions().get(index).opcode();
            if (!isDangerousClassInitAdjacency(before, after)) {
                boundaries.add(index);
            }
        }
        return List.copyOf(boundaries);
    }

    private int splitIndex(SplitCandidate candidate, String token) {
        long tokenValue = Long.parseUnsignedLong(token, 16);
        int selected = (int) Long.remainderUnsigned(tokenValue, candidate.boundaries().size());
        return candidate.boundaries().get(selected);
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

    private boolean isMonitorOrJmmSensitiveOpcode(IrOpcode opcode) {
        return opcode == IrOpcode.MONITOR_ENTER
                || opcode == IrOpcode.MONITOR_EXIT
                || opcode == IrOpcode.MONITOR_EXIT_ON_EXCEPTION
                || opcode == IrOpcode.VOLATILE_READ_BARRIER
                || opcode == IrOpcode.VOLATILE_WRITE_BARRIER
                || opcode == IrOpcode.FINAL_FIELD_PUBLICATION
                || opcode == IrOpcode.MONITOR_HAPPENS_BEFORE;
    }

    private boolean isDangerousClassInitAdjacency(IrOpcode before, IrOpcode after) {
        return isClassInitOpcode(before) || isClassInitOpcode(after);
    }

    private boolean isClassInitOpcode(IrOpcode opcode) {
        return opcode == IrOpcode.CLASS_OBJECT
                || opcode == IrOpcode.CLASS_INIT_GUARD
                || opcode == IrOpcode.CLASS_INIT_BEGIN
                || opcode == IrOpcode.CLASS_INIT_END
                || opcode == IrOpcode.CLASS_INIT_FAILED
                || opcode == IrOpcode.CLASS_INIT_HAPPENS_BEFORE
                || opcode == IrOpcode.CLASS_INIT_ACTIVE_USE;
    }

    private record SplitCandidate(IrBlock block, List<Integer> boundaries) {}
}
