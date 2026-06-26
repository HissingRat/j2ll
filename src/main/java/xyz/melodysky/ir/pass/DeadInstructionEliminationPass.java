package xyz.melodysky.ir.pass;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrValue;

public final class DeadInstructionEliminationPass implements IrMethodPass {
    @Override
    public String name() {
        return "deadInstructionElimination";
    }

    @Override
    public IrMethod run(IrMethod method, PassContext context) {
        ArrayList<IrBlock> blocks = new ArrayList<>();
        for (IrBlock block : method.blocks()) {
            Set<IrValue> live = new HashSet<>();
            block.terminator().value().ifPresent(live::add);
            block.terminator().condition().ifPresent(live::add);
            block.terminator().switchValue().ifPresent(live::add);
            live.addAll(block.terminator().targetArguments());
            live.addAll(block.terminator().trueTargetArguments());
            live.addAll(block.terminator().falseTargetArguments());
            live.addAll(block.terminator().defaultTargetArguments());
            for (var switchCase : block.terminator().switchCases()) {
                live.addAll(switchCase.arguments());
            }
            ArrayList<IrInstruction> keptReversed = new ArrayList<>();
            List<IrInstruction> instructions = block.instructions();
            for (int index = instructions.size() - 1; index >= 0; index--) {
                IrInstruction instruction = instructions.get(index);
                if (instruction.result().isEmpty()
                        || !instruction.exceptionSites().isEmpty()
                        || live.contains(instruction.result().orElseThrow())) {
                    keptReversed.add(instruction);
                    live.addAll(instruction.operands());
                }
            }
            ArrayList<IrInstruction> kept = new ArrayList<>();
            for (int index = keptReversed.size() - 1; index >= 0; index--) {
                kept.add(keptReversed.get(index));
            }
            blocks.add(new IrBlock(
                    block.name(),
                    block.parameters(),
                    block.exceptionCatchTypes(),
                    block.exceptionEdges(),
                    kept,
                    block.terminator()));
        }
        return new IrMethod(method.owner(), method.name(), method.descriptor(), method.returnType(), method.parameters(), blocks);
    }
}
