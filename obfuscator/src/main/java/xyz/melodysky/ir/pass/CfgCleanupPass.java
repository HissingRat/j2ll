package xyz.melodysky.ir.pass;

import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrTerminator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class CfgCleanupPass implements IrMethodPass {

    @Override
    public String name() {
        return "cfg-cleanup";
    }

    @Override
    public IrMethod apply(IrMethod method) {
        Map<String, IrBlock> blockByLabel = new HashMap<>();
        for (IrBlock block : method.blocks()) {
            blockByLabel.put(block.label(), block);
        }

        Set<String> reachable = new HashSet<>();
        ArrayDeque<String> worklist = new ArrayDeque<>();
        worklist.add(method.entryBlock());

        while (!worklist.isEmpty()) {
            String label = worklist.removeFirst();
            if (!reachable.add(label)) {
                continue;
            }

            IrBlock block = blockByLabel.get(label);
            if (block == null) {
                continue;
            }

            switch (block.terminator()) {
                case IrTerminator.Goto goTo -> worklist.add(goTo.targetBlock());
                case IrTerminator.Branch branch -> {
                    worklist.add(branch.trueTarget());
                    worklist.add(branch.falseTarget());
                }
                case IrTerminator.Switch switchTerminator -> {
                    worklist.add(switchTerminator.defaultTarget());
                    worklist.addAll(switchTerminator.targetByKey().values());
                }
                case IrTerminator.Return ignored -> {
                }
                case IrTerminator.ReturnVoid ignored -> {
                }
                case IrTerminator.Throw ignored -> {
                }
                case IrTerminator.Unreachable ignored -> {
                }
            }
        }

        ArrayList<IrBlock> cleanedBlocks = new ArrayList<>();
        for (IrBlock block : method.blocks()) {
            if (reachable.contains(block.label())) {
                cleanedBlocks.add(block);
            }
        }

        return new IrMethod(
                method.name(),
                method.returnType(),
                method.parameterTypes(),
                method.maxLocals(),
                method.isStatic(),
                method.entryBlock(),
                cleanedBlocks
        );
    }
}
