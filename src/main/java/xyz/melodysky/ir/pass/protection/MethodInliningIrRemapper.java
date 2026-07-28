package xyz.melodysky.ir.pass.protection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrExceptionEdge;
import xyz.melodysky.ir.model.IrExceptionSite;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrSwitchCase;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrValue;

final class MethodInliningIrRemapper {
    private final Map<IrValue, IrValue> values = new HashMap<>();
    private final Map<String, String> blocks = new HashMap<>();

    MethodInliningIrRemapper(
            IrMethod callee,
            List<IrValue> callOperands,
            MethodInliningNames names) {
        if (callee.parameters().size() != callOperands.size()) {
            throw new IllegalArgumentException("call operand count does not match callee parameters");
        }
        for (int index = 0; index < callee.parameters().size(); index++) {
            IrValue parameter = callee.parameters().get(index);
            IrValue operand = callOperands.get(index);
            if (parameter.type() != operand.type()) {
                throw new IllegalArgumentException("call operand type does not match callee parameter");
            }
            putValue(parameter, operand);
        }
        for (IrBlock block : callee.blocks()) {
            if (blocks.put(block.name(), names.nextBlock()) != null) {
                throw new IllegalArgumentException("duplicate callee block name");
            }
            for (IrValue parameter : block.parameters()) {
                putValue(parameter, names.nextValue(parameter.type()));
            }
            for (IrInstruction instruction : block.instructions()) {
                instruction.result().ifPresent(result -> putValue(result, names.nextValue(result.type())));
            }
        }
    }

    String blockName(String original) {
        String mapped = blocks.get(original);
        if (mapped == null) {
            throw new IllegalArgumentException("callee targets unknown block: " + original);
        }
        return mapped;
    }

    IrBlock cloneBlock(
            IrBlock block,
            String continuationBlock,
            IrValue continuationParameter) {
        List<IrInstruction> instructions = block.instructions().stream()
                .map(this::cloneInstruction)
                .toList();
        return new IrBlock(
                blockName(block.name()),
                remapValues(block.parameters()),
                block.exceptionCatchTypes(),
                remapExceptionEdges(block.exceptionEdges()),
                instructions,
                cloneTerminator(block.terminator(), continuationBlock, continuationParameter));
    }

    private IrInstruction cloneInstruction(IrInstruction instruction) {
        List<IrExceptionSite> sites = instruction.exceptionSites().stream()
                .map(site -> new IrExceptionSite(
                        site.kind(),
                        remapExceptionEdges(site.handlers()),
                        site.exceptionValue().map(this::value)))
                .toList();
        return new IrInstruction(
                instruction.result().map(this::value),
                instruction.opcode(),
                remapValues(instruction.operands()),
                instruction.intLiteral(),
                instruction.longLiteral(),
                instruction.floatLiteral(),
                instruction.doubleLiteral(),
                instruction.symbol(),
                sites);
    }

    private IrTerminator cloneTerminator(
            IrTerminator terminator,
            String continuationBlock,
            IrValue continuationParameter) {
        return switch (terminator.kind()) {
            case RETURN -> {
                List<IrValue> arguments = terminator.value().stream().map(this::value).toList();
                if ((continuationParameter == null) != arguments.isEmpty()) {
                    throw new IllegalArgumentException("callee return shape does not match continuation");
                }
                yield IrTerminator.gotoBlock(continuationBlock, arguments);
            }
            case THROW -> throw new IllegalArgumentException("throwing callee cannot be inlined");
            case GOTO -> IrTerminator.gotoBlock(
                    blockName(terminator.target().orElseThrow()),
                    remapValues(terminator.targetArguments()));
            case BRANCH -> IrTerminator.branch(
                    value(terminator.condition().orElseThrow()),
                    blockName(terminator.trueTarget().orElseThrow()),
                    remapValues(terminator.trueTargetArguments()),
                    blockName(terminator.falseTarget().orElseThrow()),
                    remapValues(terminator.falseTargetArguments()));
            case SWITCH -> {
                ArrayList<IrSwitchCase> cases = new ArrayList<>();
                for (IrSwitchCase switchCase : terminator.switchCases()) {
                    cases.add(new IrSwitchCase(
                            switchCase.key(),
                            blockName(switchCase.target()),
                            remapValues(switchCase.arguments())));
                }
                yield IrTerminator.switchOn(
                        value(terminator.switchValue().orElseThrow()),
                        blockName(terminator.defaultTarget().orElseThrow()),
                        remapValues(terminator.defaultTargetArguments()),
                        cases);
            }
        };
    }

    private List<IrExceptionEdge> remapExceptionEdges(List<IrExceptionEdge> edges) {
        return edges.stream()
                .map(edge -> new IrExceptionEdge(
                        blockName(edge.target()),
                        edge.catchType(),
                        remapValues(edge.arguments())))
                .toList();
    }

    private List<IrValue> remapValues(List<IrValue> original) {
        return original.stream().map(this::value).toList();
    }

    private IrValue value(IrValue original) {
        IrValue mapped = values.get(original);
        if (mapped == null) {
            throw new IllegalArgumentException("callee uses unmapped value: " + original.name());
        }
        return mapped;
    }

    private void putValue(IrValue original, IrValue mapped) {
        if (values.putIfAbsent(original, mapped) != null) {
            throw new IllegalArgumentException("duplicate callee value definition: " + original.name());
        }
    }
}
