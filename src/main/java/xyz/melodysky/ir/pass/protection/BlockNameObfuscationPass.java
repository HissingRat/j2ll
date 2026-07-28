package xyz.melodysky.ir.pass.protection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import xyz.melodysky.diagnostic.DiagnosticSeverity;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrExceptionEdge;
import xyz.melodysky.ir.model.IrExceptionSite;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrSwitchCase;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.validate.IrMethodValidator;

public final class BlockNameObfuscationPass implements ProtectionPass {
    @Override
    public String name() {
        return "BLOCK_NAME_OBFUSCATION";
    }

    @Override
    public boolean enabled(ProtectionConfig config) {
        return config.enabled() && config.blockNameObfuscation();
    }

    @Override
    public IrMethod run(IrMethod method, ProtectionConfig config) {
        if (!config.enabled()) {
            return method;
        }
        ProtectionRandom random = new ProtectionRandom(config.seed());
        HashMap<String, String> renamed = new HashMap<>();
        for (IrBlock block : method.blocks()) {
            String token = random.token(name(), method.methodKey() + ":" + block.name(), 12);
            renamed.put(block.name(), "bb_" + token);
        }
        ArrayList<IrBlock> blocks = new ArrayList<>();
        for (IrBlock block : method.blocks()) {
            blocks.add(new IrBlock(
                    renamed.get(block.name()),
                    block.parameters(),
                    block.exceptionCatchTypes(),
                    renameExceptionEdges(block.exceptionEdges(), renamed),
                    renameInstructions(block.instructions(), renamed),
                    renameTerminator(block.terminator(), renamed)));
        }
        IrMethod candidate = new IrMethod(
                method.owner(),
                method.name(),
                method.descriptor(),
                method.returnType(),
                method.parameters(),
                blocks);
        boolean invalid = new IrMethodValidator().validate(candidate).stream()
                .anyMatch(diagnostic -> diagnostic.severity() == DiagnosticSeverity.ERROR);
        return invalid ? method : candidate;
    }

    private List<IrInstruction> renameInstructions(
            List<IrInstruction> instructions,
            Map<String, String> renamed) {
        return instructions.stream()
                .map(instruction -> renameInstruction(instruction, renamed))
                .toList();
    }

    private IrInstruction renameInstruction(
            IrInstruction instruction,
            Map<String, String> renamed) {
        if (instruction.exceptionSites().isEmpty()) {
            return instruction;
        }
        List<IrExceptionSite> exceptionSites = instruction.exceptionSites().stream()
                .map(site -> new IrExceptionSite(
                        site.kind(),
                        renameExceptionEdges(site.handlers(), renamed),
                        site.exceptionValue()))
                .toList();
        return new IrInstruction(
                instruction.result(),
                instruction.opcode(),
                instruction.operands(),
                instruction.intLiteral(),
                instruction.longLiteral(),
                instruction.floatLiteral(),
                instruction.doubleLiteral(),
                instruction.symbol(),
                exceptionSites,
                instruction.callIndirection());
    }

    private List<IrExceptionEdge> renameExceptionEdges(
            List<IrExceptionEdge> exceptionEdges,
            Map<String, String> renamed) {
        return exceptionEdges.stream()
                .map(edge -> new IrExceptionEdge(
                        renameTarget(edge.target(), renamed),
                        edge.catchType(),
                        edge.arguments()))
                .toList();
    }

    private IrTerminator renameTerminator(IrTerminator terminator, Map<String, String> renamed) {
        return switch (terminator.kind()) {
            case RETURN, THROW -> terminator;
            case GOTO -> IrTerminator.gotoBlock(
                    renameTarget(terminator.target().orElseThrow(), renamed),
                    terminator.targetArguments());
            case BRANCH -> IrTerminator.branch(
                    terminator.condition().orElseThrow(),
                    renameTarget(terminator.trueTarget().orElseThrow(), renamed),
                    terminator.trueTargetArguments(),
                    renameTarget(terminator.falseTarget().orElseThrow(), renamed),
                    terminator.falseTargetArguments());
            case SWITCH -> {
                ArrayList<IrSwitchCase> cases = new ArrayList<>();
                for (IrSwitchCase switchCase : terminator.switchCases()) {
                    cases.add(new IrSwitchCase(
                            switchCase.key(),
                            renameTarget(switchCase.target(), renamed),
                            switchCase.arguments()));
                }
                yield IrTerminator.switchOn(
                        terminator.switchValue().orElseThrow(),
                        renameTarget(terminator.defaultTarget().orElseThrow(), renamed),
                        terminator.defaultTargetArguments(),
                        cases);
            }
        };
    }

    private String renameTarget(String target, Map<String, String> renamed) {
        String renamedTarget = renamed.get(target);
        if (renamedTarget == null) {
            throw new IllegalArgumentException("block rename target does not exist: " + target);
        }
        return renamedTarget;
    }
}
