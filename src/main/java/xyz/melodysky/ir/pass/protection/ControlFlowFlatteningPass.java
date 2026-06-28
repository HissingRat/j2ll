package xyz.melodysky.ir.pass.protection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrSwitchCase;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrTerminatorKind;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;

public final class ControlFlowFlatteningPass implements ProtectionPass {
    @Override
    public String name() {
        return "CONTROL_FLOW_FLATTENING";
    }

    @Override
    public boolean enabled(ProtectionConfig config) {
        return config.enabled() && config.controlFlowFlattening();
    }

    @Override
    public boolean applicable(IrMethod method) {
        if (isStubBackedMethod(method)) {
            return false;
        }
        return isSafeShape(method);
    }

    @Override
    public String skipReasonCode(IrMethod method) {
        if (isStubBackedMethod(method)) {
            return "PROTECTION_STUB_BACKED_METHOD";
        }
        return "CONTROL_FLOW_FLATTENING_UNSUPPORTED_SHAPE";
    }

    @Override
    public IrMethod run(IrMethod method, ProtectionConfig config) {
        if (!enabled(config) || !applicable(method)) {
            return method;
        }
        ProtectionRandom random = new ProtectionRandom(config.seed());
        String token = random.token(name(), method.methodKey(), 10);
        Map<String, Integer> states = new LinkedHashMap<>();
        for (int index = 0; index < method.blocks().size(); index++) {
            states.put(method.blocks().get(index).name(), index);
        }

        String dispatcherName = "cff_dispatch_" + token;
        IrValue dispatcherState = new IrValue("%j2ll_cff_" + token + "_state", IrType.I32);
        ArrayList<IrBlock> flattened = new ArrayList<>();
        flattened.add(entryBlock(method.blocks().get(0).name(), dispatcherName, token, states.get(method.blocks().get(0).name())));
        flattened.add(dispatcherBlock(dispatcherName, dispatcherState, states, token));
        for (IrBlock block : method.blocks()) {
            flattened.add(bodyBlock(block, dispatcherName, token, states));
            if (block.terminator().kind() == IrTerminatorKind.BRANCH) {
                flattened.add(transitionBlock(
                        transitionName(block.name(), "true", token),
                        dispatcherName,
                        token,
                        states.get(block.terminator().trueTarget().orElseThrow())));
                flattened.add(transitionBlock(
                        transitionName(block.name(), "false", token),
                        dispatcherName,
                        token,
                        states.get(block.terminator().falseTarget().orElseThrow())));
            }
        }
        return new IrMethod(
                method.owner(),
                method.name(),
                method.descriptor(),
                method.returnType(),
                method.parameters(),
                flattened);
    }

    private IrBlock entryBlock(String originalEntryName, String dispatcherName, String token, int state) {
        IrValue initialState = new IrValue("%j2ll_cff_" + token + "_initial", IrType.I32);
        return new IrBlock(
                originalEntryName,
                List.of(IrInstruction.constInt(initialState, state)),
                IrTerminator.gotoBlock(dispatcherName, List.of(initialState)));
    }

    private IrBlock dispatcherBlock(
            String dispatcherName,
            IrValue dispatcherState,
            Map<String, Integer> states,
            String token) {
        String defaultTarget = bodyName(states.keySet().iterator().next(), token);
        List<IrSwitchCase> cases = states.entrySet().stream()
                .skip(1)
                .map(entry -> new IrSwitchCase(entry.getValue(), bodyName(entry.getKey(), token)))
                .toList();
        return new IrBlock(
                dispatcherName,
                List.of(dispatcherState),
                List.of(),
                IrTerminator.switchOn(dispatcherState, defaultTarget, cases));
    }

    private IrBlock bodyBlock(IrBlock block, String dispatcherName, String token, Map<String, Integer> states) {
        ArrayList<IrInstruction> instructions = new ArrayList<>(block.instructions());
        IrTerminator terminator = block.terminator();
        if (terminator.kind() == IrTerminatorKind.GOTO) {
            IrValue state = new IrValue("%j2ll_cff_" + token + "_" + states.get(block.name()), IrType.I32);
            instructions.add(IrInstruction.constInt(state, states.get(terminator.target().orElseThrow())));
            terminator = IrTerminator.gotoBlock(dispatcherName, List.of(state));
        } else {
            terminator = rewriteTerminator(block, token);
        }
        return new IrBlock(
                bodyName(block.name(), token),
                List.of(),
                block.exceptionCatchTypes(),
                block.exceptionEdges(),
                instructions,
                terminator);
    }

    private IrTerminator rewriteTerminator(
            IrBlock block,
            String token) {
        IrTerminator terminator = block.terminator();
        if (terminator.kind() == IrTerminatorKind.RETURN) {
            return terminator;
        }
        if (terminator.kind() == IrTerminatorKind.BRANCH) {
            return IrTerminator.branch(
                    terminator.condition().orElseThrow(),
                    transitionName(block.name(), "true", token),
                    transitionName(block.name(), "false", token));
        }
        throw new IllegalStateException("unsupported terminator reached CFF: " + terminator.kind());
    }

    private IrBlock transitionBlock(String name, String dispatcherName, String token, int state) {
        IrValue stateValue = new IrValue("%j2ll_cff_" + token + "_" + name, IrType.I32);
        return new IrBlock(
                name,
                List.of(IrInstruction.constInt(stateValue, state)),
                IrTerminator.gotoBlock(dispatcherName, List.of(stateValue)));
    }

    private String bodyName(String original, String token) {
        return "cff_body_" + token + "_" + original;
    }

    private String transitionName(String original, String edge, String token) {
        return "cff_" + edge + "_" + token + "_" + original;
    }

    private boolean isSafeShape(IrMethod method) {
        if (method.blocks().size() < 2) {
            return false;
        }
        if (!isPrimitiveOrVoid(method.returnType())
                || method.parameters().stream().map(IrValue::type).anyMatch(type -> !isPrimitive(type))) {
            return false;
        }
        for (IrBlock block : method.blocks()) {
            if (!block.parameters().isEmpty()
                    || block.isExceptionHandler()
                    || !block.exceptionEdges().isEmpty()
                    || !block.exceptionCatchTypes().isEmpty()) {
                return false;
            }
            if (!block.terminator().targetArguments().isEmpty()
                    || !block.terminator().trueTargetArguments().isEmpty()
                    || !block.terminator().falseTargetArguments().isEmpty()
                    || !block.terminator().defaultTargetArguments().isEmpty()
                    || block.terminator().switchCases().stream().anyMatch(switchCase -> !switchCase.arguments().isEmpty())) {
                return false;
            }
            if (block.terminator().kind() == IrTerminatorKind.THROW
                    || block.terminator().kind() == IrTerminatorKind.SWITCH) {
                return false;
            }
            if (block.instructions().stream().anyMatch(instruction -> !instruction.exceptionSites().isEmpty()
                    || isSensitiveOpcode(instruction.opcode())
                    || instruction.result().map(IrValue::type).filter(type -> !isPrimitive(type)).isPresent()
                    || instruction.operands().stream().map(IrValue::type).anyMatch(type -> !isPrimitive(type)))) {
                return false;
            }
            if (block.terminator().value().map(IrValue::type).filter(type -> !isPrimitive(type)).isPresent()
                    || block.terminator().condition().map(IrValue::type).filter(type -> type != IrType.I1).isPresent()) {
                return false;
            }
        }
        return true;
    }

    private boolean isPrimitiveOrVoid(IrType type) {
        return type == IrType.VOID || isPrimitive(type);
    }

    private boolean isPrimitive(IrType type) {
        return type == IrType.I1
                || type == IrType.I32
                || type == IrType.I64
                || type == IrType.F32
                || type == IrType.F64;
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
