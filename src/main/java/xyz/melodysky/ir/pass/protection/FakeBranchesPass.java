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
import xyz.melodysky.ir.model.IrTerminatorKind;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;

public final class FakeBranchesPass implements ProtectionPass {
    @Override
    public String name() {
        return "FAKE_BRANCHES";
    }

    @Override
    public boolean enabled(ProtectionConfig config) {
        return config.enabled() && config.fakeBranches();
    }

    @Override
    public boolean applicable(IrMethod method) {
        if (isStubBackedMethod(method) || method.blocks().isEmpty()) {
            return false;
        }
        IrBlock entry = method.blocks().get(0);
        if (!entry.parameters().isEmpty()
                || entry.isExceptionHandler()
                || hasIncomingEdge(method, entry.name())) {
            return false;
        }
        return method.blocks().stream().allMatch(this::isSafeBlock);
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
        ProtectionRandom random = new ProtectionRandom(config.seed());
        String token = random.token(name(), method.methodKey(), 10);
        String originalEntry = method.blocks().get(0).name();
        String gateName = uniqueBlockName(method, "fake_gate_" + token);
        String detourName = uniqueBlockName(method, "fake_detour_" + token);

        ArrayList<IrInstruction> predicateInstructions = new ArrayList<>();
        IrValue condition = buildPredicate(method, token, predicateInstructions);
        IrBlock gate = new IrBlock(
                gateName,
                predicateInstructions,
                IrTerminator.branch(condition, originalEntry, detourName));
        IrBlock detour = new IrBlock(detourName, List.of(), IrTerminator.gotoBlock(originalEntry));

        ArrayList<IrBlock> blocks = new ArrayList<>(method.blocks().size() + 2);
        blocks.add(gate);
        blocks.add(detour);
        blocks.addAll(method.blocks());
        return new IrMethod(
                method.owner(),
                method.name(),
                method.descriptor(),
                method.returnType(),
                method.parameters(),
                blocks);
    }

    private IrValue buildPredicate(
            IrMethod method,
            String token,
            List<IrInstruction> instructions) {
        IrValue i1Input = method.parameters().stream()
                .filter(parameter -> parameter.type() == IrType.I1)
                .findFirst()
                .orElse(null);
        if (i1Input != null) {
            return i1Input;
        }

        IrValue i32Input = method.parameters().stream()
                .filter(parameter -> parameter.type() == IrType.I32)
                .findFirst()
                .orElse(null);
        if (i32Input != null) {
            int mask = (int) Long.parseUnsignedLong(token.substring(0, 8), 16);
            IrValue maskValue = new IrValue("%j2ll_fb_" + token + "_mask", IrType.I32);
            IrValue mixed = new IrValue("%j2ll_fb_" + token + "_mixed", IrType.I32);
            IrValue one = new IrValue("%j2ll_fb_" + token + "_one", IrType.I32);
            IrValue parity = new IrValue("%j2ll_fb_" + token + "_parity", IrType.I32);
            IrValue zero = new IrValue("%j2ll_fb_" + token + "_zero", IrType.I32);
            IrValue condition = new IrValue("%j2ll_fb_" + token + "_condition", IrType.I1);
            instructions.add(IrInstruction.constInt(maskValue, mask));
            instructions.add(IrInstruction.binary(mixed, IrOpcode.XOR_I32, i32Input, maskValue));
            instructions.add(IrInstruction.constInt(one, 1));
            instructions.add(IrInstruction.binary(parity, IrOpcode.AND_I32, mixed, one));
            instructions.add(IrInstruction.constInt(zero, 0));
            instructions.add(IrInstruction.binary(condition, IrOpcode.CMP_EQ_I32, parity, zero));
            return condition;
        }

        IrValue referenceInput = method.parameters().stream()
                .filter(parameter -> parameter.type() == IrType.REFERENCE)
                .findFirst()
                .orElse(null);
        if (referenceInput != null) {
            IrValue nullValue = new IrValue("%j2ll_fb_" + token + "_null", IrType.REFERENCE);
            IrValue condition = new IrValue("%j2ll_fb_" + token + "_condition", IrType.I1);
            instructions.add(IrInstruction.constNull(nullValue));
            instructions.add(IrInstruction.binary(condition, IrOpcode.CMP_EQ_REF, referenceInput, nullValue));
            return condition;
        }

        int leftLiteral = (int) Long.parseUnsignedLong(token.substring(0, 8), 16);
        int rightLiteral = Integer.rotateLeft(leftLiteral ^ 0x9e3779b9, 13);
        IrValue left = new IrValue("%j2ll_fb_" + token + "_left", IrType.I32);
        IrValue right = new IrValue("%j2ll_fb_" + token + "_right", IrType.I32);
        IrValue condition = new IrValue("%j2ll_fb_" + token + "_condition", IrType.I1);
        instructions.add(IrInstruction.constInt(left, leftLiteral));
        instructions.add(IrInstruction.constInt(right, rightLiteral));
        instructions.add(IrInstruction.binary(condition, IrOpcode.CMP_NE_I32, left, right));
        return condition;
    }

    private boolean hasIncomingEdge(IrMethod method, String entryName) {
        for (IrBlock block : method.blocks()) {
            if (block.exceptionEdges().stream().anyMatch(edge -> edge.target().equals(entryName))) {
                return true;
            }
            IrTerminator terminator = block.terminator();
            if (terminator.target().filter(entryName::equals).isPresent()
                    || terminator.trueTarget().filter(entryName::equals).isPresent()
                    || terminator.falseTarget().filter(entryName::equals).isPresent()
                    || terminator.defaultTarget().filter(entryName::equals).isPresent()
                    || terminator.switchCases().stream().anyMatch(switchCase -> switchCase.target().equals(entryName))) {
                return true;
            }
        }
        return false;
    }

    private boolean isSafeBlock(IrBlock block) {
        if (!block.parameters().isEmpty()
                || block.isExceptionHandler()
                || !block.exceptionCatchTypes().isEmpty()
                || !block.exceptionEdges().isEmpty()) {
            return false;
        }
        return block.instructions().stream().allMatch(instruction -> instruction.exceptionSites().isEmpty()
                && !isSensitiveOpcode(instruction.opcode()));
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
