package xyz.melodysky.ir.pass.protection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
    private static final String OWNED_LOCAL_REFERENCE_REASON =
            "CONTROL_FLOW_FLATTENING_OWNED_LOCAL_REFERENCE";

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
        if (hasSupportedStructuralShape(method)
                && createsOwnedLocalReference(method)) {
            return OWNED_LOCAL_REFERENCE_REASON;
        }
        if (hasSupportedStructuralShape(method) && hasCrossBlockInstructionValueUse(method)) {
            return "CONTROL_FLOW_FLATTENING_CROSS_BLOCK_SSA_VALUE";
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
        Map<String, Integer> states = statePermutation(
                method,
                random);

        String dispatcherName = "cff_dispatch_" + token;
        IrValue dispatcherState = new IrValue("%j2ll_cff_" + token + "_state", IrType.I32);
        ArrayList<IrBlock> flattened = new ArrayList<>();
        flattened.add(entryBlock(method.blocks().get(0).name(), dispatcherName, token, states.get(method.blocks().get(0).name())));
        flattened.add(dispatcherBlock(
                dispatcherName,
                dispatcherState,
                states,
                token,
                method.methodKey(),
                random));
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

    private Map<String, Integer> statePermutation(
            IrMethod method,
            ProtectionRandom random) {
        ArrayList<IrBlock> ranked = new ArrayList<>(method.blocks());
        ranked.sort(Comparator
                .comparing((IrBlock block) -> random.token(
                        name() + ":STATE_RANK",
                        method.methodKey() + ":" + block.name(),
                        64))
                .thenComparing(IrBlock::name));
        LinkedHashMap<String, Integer> states = new LinkedHashMap<>();
        for (int state = 0; state < ranked.size(); state++) {
            states.put(ranked.get(state).name(), state);
        }
        return states;
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
            String token,
            String methodKey,
            ProtectionRandom random) {
        String defaultBlock = states.keySet().stream()
                .min(Comparator
                        .comparing((String block) -> random.token(
                                name() + ":DEFAULT_TARGET",
                                methodKey + ":" + block,
                                64))
                        .thenComparing(block -> block))
                .orElseThrow();
        String defaultTarget = bodyName(defaultBlock, token);
        List<IrSwitchCase> cases = states.entrySet().stream()
                .filter(entry -> !entry.getKey().equals(defaultBlock))
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
        return hasSupportedStructuralShape(method)
                && !createsOwnedLocalReference(method)
                && !hasCrossBlockInstructionValueUse(method);
    }

    private boolean createsOwnedLocalReference(IrMethod method) {
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(this::createsOwnedLocalReference);
    }

    private boolean createsOwnedLocalReference(IrInstruction instruction) {
        boolean referenceResult = instruction.result()
                .map(result -> result.type() == IrType.REFERENCE)
                .orElse(false);
        boolean borrowedOrNullResult = instruction.opcode() == IrOpcode.CONST_NULL
                || instruction.opcode() == IrOpcode.CHECKCAST;
        boolean exceptionReference = instruction.exceptionSites().stream()
                .anyMatch(site -> site.exceptionValue().isPresent());
        return (referenceResult && !borrowedOrNullResult)
                || exceptionReference;
    }

    private boolean hasSupportedStructuralShape(IrMethod method) {
        if (method.blocks().size() < 2) {
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
            if (!hasSafeClassInitializationOrdering(block)
                    || block.instructions().stream().anyMatch(instruction ->
                            instruction.exceptionSites().stream().anyMatch(site -> !site.handlers().isEmpty())
                                    || isMonitorOrJmmSensitiveOpcode(instruction.opcode()))) {
                return false;
            }
            if (block.terminator().condition().map(IrValue::type).filter(type -> type != IrType.I1).isPresent()) {
                return false;
            }
        }
        return true;
    }

    private boolean hasCrossBlockInstructionValueUse(IrMethod method) {
        HashMap<IrValue, String> definitionBlocks = new HashMap<>();
        for (IrBlock block : method.blocks()) {
            for (IrInstruction instruction : block.instructions()) {
                instruction.result().ifPresent(result -> definitionBlocks.put(result, block.name()));
                instruction.exceptionSites().forEach(site -> site.exceptionValue()
                        .ifPresent(result -> definitionBlocks.put(result, block.name())));
            }
        }
        for (IrBlock block : method.blocks()) {
            for (IrInstruction instruction : block.instructions()) {
                if (usesDefinitionFromAnotherBlock(
                        block.name(),
                        instruction.operands(),
                        definitionBlocks)) {
                    return true;
                }
                boolean crossBlockHandlerArgument = instruction.exceptionSites().stream()
                        .flatMap(site -> site.handlers().stream())
                        .anyMatch(edge -> usesDefinitionFromAnotherBlock(
                                block.name(),
                                edge.arguments(),
                                definitionBlocks));
                if (crossBlockHandlerArgument) {
                    return true;
                }
            }
            IrTerminator terminator = block.terminator();
            if (usesDefinitionFromAnotherBlock(
                            block.name(),
                            terminator.value().stream().toList(),
                            definitionBlocks)
                    || usesDefinitionFromAnotherBlock(
                            block.name(),
                            terminator.condition().stream().toList(),
                            definitionBlocks)
                    || usesDefinitionFromAnotherBlock(
                            block.name(),
                            terminator.switchValue().stream().toList(),
                            definitionBlocks)
                    || usesDefinitionFromAnotherBlock(
                            block.name(),
                            terminator.targetArguments(),
                            definitionBlocks)
                    || usesDefinitionFromAnotherBlock(
                            block.name(),
                            terminator.trueTargetArguments(),
                            definitionBlocks)
                    || usesDefinitionFromAnotherBlock(
                            block.name(),
                            terminator.falseTargetArguments(),
                            definitionBlocks)
                    || usesDefinitionFromAnotherBlock(
                            block.name(),
                            terminator.defaultTargetArguments(),
                            definitionBlocks)
                    || terminator.switchCases().stream().anyMatch(switchCase ->
                            usesDefinitionFromAnotherBlock(
                                    block.name(),
                                    switchCase.arguments(),
                                    definitionBlocks))
                    || block.exceptionEdges().stream().anyMatch(edge ->
                            usesDefinitionFromAnotherBlock(
                                    block.name(),
                                    edge.arguments(),
                                    definitionBlocks))) {
                return true;
            }
        }
        return false;
    }

    private boolean usesDefinitionFromAnotherBlock(
            String useBlock,
            List<IrValue> values,
            Map<IrValue, String> definitionBlocks) {
        return values.stream()
                .map(definitionBlocks::get)
                .anyMatch(definitionBlock ->
                        definitionBlock != null && !definitionBlock.equals(useBlock));
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

    private boolean hasSafeClassInitializationOrdering(IrBlock block) {
        List<IrInstruction> instructions = block.instructions();
        for (int index = 0; index < instructions.size(); index++) {
            IrInstruction instruction = instructions.get(index);
            if (instruction.opcode() == IrOpcode.CLASS_INIT_BEGIN
                    || instruction.opcode() == IrOpcode.CLASS_INIT_END
                    || instruction.opcode() == IrOpcode.CLASS_INIT_FAILED) {
                return false;
            }
            if (instruction.opcode() == IrOpcode.CLASS_INIT_GUARD) {
                if (index == 0
                        || index + 1 >= instructions.size()
                        || instructions.get(index - 1).opcode() != IrOpcode.CLASS_OBJECT
                        || instructions.get(index + 1).opcode() != IrOpcode.CLASS_INIT_HAPPENS_BEFORE
                        || instruction.operands().size() != 1
                        || instructions.get(index - 1).result().isEmpty()
                        || !instructions.get(index - 1).result().orElseThrow().equals(instruction.operands().get(0))
                        || !instructions.get(index + 1).operands().equals(instruction.operands())) {
                    return false;
                }
            }
            if (instruction.opcode() == IrOpcode.CLASS_INIT_HAPPENS_BEFORE
                    && (index == 0 || instructions.get(index - 1).opcode() != IrOpcode.CLASS_INIT_GUARD)) {
                return false;
            }
        }
        return true;
    }
}
