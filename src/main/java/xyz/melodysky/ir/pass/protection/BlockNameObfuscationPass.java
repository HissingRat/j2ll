package xyz.melodysky.ir.pass.protection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrSwitchCase;
import xyz.melodysky.ir.model.IrTerminator;

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
                    block.instructions(),
                    renameTerminator(block.terminator(), renamed)));
        }
        return new IrMethod(method.owner(), method.name(), method.descriptor(), method.returnType(), method.parameters(), blocks);
    }

    private java.util.List<xyz.melodysky.ir.model.IrExceptionEdge> renameExceptionEdges(
            java.util.List<xyz.melodysky.ir.model.IrExceptionEdge> exceptionEdges,
            Map<String, String> renamed) {
        return exceptionEdges.stream()
                .map(edge -> new xyz.melodysky.ir.model.IrExceptionEdge(
                        renamed.get(edge.target()),
                        edge.catchType()))
                .toList();
    }

    private IrTerminator renameTerminator(IrTerminator terminator, Map<String, String> renamed) {
        return switch (terminator.kind()) {
            case RETURN, THROW -> terminator;
            case GOTO -> IrTerminator.gotoBlock(
                    renamed.get(terminator.target().orElseThrow()),
                    terminator.targetArguments());
            case BRANCH -> IrTerminator.branch(
                    terminator.condition().orElseThrow(),
                    renamed.get(terminator.trueTarget().orElseThrow()),
                    terminator.trueTargetArguments(),
                    renamed.get(terminator.falseTarget().orElseThrow()),
                    terminator.falseTargetArguments());
            case SWITCH -> {
                ArrayList<IrSwitchCase> cases = new ArrayList<>();
                for (IrSwitchCase switchCase : terminator.switchCases()) {
                    cases.add(new IrSwitchCase(
                            switchCase.key(),
                            renamed.get(switchCase.target()),
                            switchCase.arguments()));
                }
                yield IrTerminator.switchOn(
                        terminator.switchValue().orElseThrow(),
                        renamed.get(terminator.defaultTarget().orElseThrow()),
                        terminator.defaultTargetArguments(),
                        cases);
            }
        };
    }
}
