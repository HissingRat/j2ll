package xyz.melodysky.ir.pass.protection;

import java.util.ArrayList;
import java.util.List;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrTerminatorKind;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;

public final class BasicBlockSplittingPass implements ProtectionPass {
    @Override
    public String name() {
        return "BASIC_BLOCK_SPLITTING";
    }

    @Override
    public boolean enabled(ProtectionConfig config) {
        return config.enabled() && (config.basicBlockSplitting() || config.fakeBranches());
    }

    @Override
    public boolean applicable(IrMethod method) {
        if (isStubBackedMethod(method)) {
            return false;
        }
        return isSafeSingleBlock(method);
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
        IrBlock original = method.blocks().get(0);
        ProtectionRandom random = new ProtectionRandom(config.seed());
        String token = random.token(name(), method.methodKey(), 10);
        String entryName = original.name();
        String fakeName = "fake_" + token;
        String bodyName = "split_" + token;

        ArrayList<IrInstruction> entryInstructions = new ArrayList<>();
        IrValue left = new IrValue("%j2ll_fb_" + token + "_a", IrType.I32);
        IrValue right = new IrValue("%j2ll_fb_" + token + "_b", IrType.I32);
        IrValue condition = new IrValue("%j2ll_fb_" + token + "_cond", IrType.I1);
        entryInstructions.add(IrInstruction.constInt(left, 1));
        entryInstructions.add(IrInstruction.constInt(right, 1));
        entryInstructions.add(IrInstruction.binary(condition, IrOpcode.CMP_EQ_I32, left, right));

        IrBlock entry = new IrBlock(
                entryName,
                List.of(),
                entryInstructions,
                IrTerminator.branch(condition, bodyName, fakeName));
        IrBlock fake = new IrBlock(fakeName, List.of(), IrTerminator.gotoBlock(bodyName));
        IrBlock body = new IrBlock(
                bodyName,
                List.of(),
                original.exceptionCatchTypes(),
                original.exceptionEdges(),
                original.instructions(),
                original.terminator());
        return new IrMethod(
                method.owner(),
                method.name(),
                method.descriptor(),
                method.returnType(),
                method.parameters(),
                List.of(entry, fake, body));
    }

    private boolean isSafeSingleBlock(IrMethod method) {
        if (method.blocks().size() != 1) {
            return false;
        }
        IrBlock block = method.blocks().get(0);
        if (!block.parameters().isEmpty()
                || block.isExceptionHandler()
                || !block.exceptionEdges().isEmpty()
                || block.terminator().kind() != IrTerminatorKind.RETURN) {
            return false;
        }
        return block.instructions().stream().allMatch(instruction -> instruction.exceptionSites().isEmpty()
                && !isSensitiveOpcode(instruction.opcode()));
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
