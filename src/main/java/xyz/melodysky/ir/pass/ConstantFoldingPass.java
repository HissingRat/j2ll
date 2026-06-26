package xyz.melodysky.ir.pass;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrValue;

public final class ConstantFoldingPass implements IrMethodPass {
    @Override
    public String name() {
        return "constantFolding";
    }

    @Override
    public IrMethod run(IrMethod method, PassContext context) {
        ArrayList<IrBlock> blocks = new ArrayList<>();
        for (IrBlock block : method.blocks()) {
            Map<IrValue, Integer> constants = new HashMap<>();
            ArrayList<IrInstruction> folded = new ArrayList<>();
            for (IrInstruction instruction : block.instructions()) {
                IrInstruction replacement = fold(instruction, constants);
                replacement.result().ifPresent(result -> {
                    if (replacement.opcode() == IrOpcode.CONST_INT) {
                        constants.put(result, replacement.intLiteral().orElseThrow());
                    } else {
                        constants.remove(result);
                    }
                });
                folded.add(replacement);
            }
            blocks.add(new IrBlock(
                    block.name(),
                    block.parameters(),
                    block.exceptionCatchTypes(),
                    block.exceptionEdges(),
                    folded,
                    block.terminator()));
        }
        return new IrMethod(method.owner(), method.name(), method.descriptor(), method.returnType(), method.parameters(), blocks);
    }

    private IrInstruction fold(IrInstruction instruction, Map<IrValue, Integer> constants) {
        if (instruction.opcode() == IrOpcode.CONST_INT) {
            return instruction;
        }
        if (instruction.operands().size() != 2 || instruction.result().isEmpty()) {
            return instruction;
        }
        Integer left = constants.get(instruction.operands().get(0));
        Integer right = constants.get(instruction.operands().get(1));
        if (left == null || right == null) {
            return instruction;
        }
        return switch (instruction.opcode()) {
            case ADD_I32 -> IrInstruction.constInt(instruction.result().orElseThrow(), left + right);
            case SUB_I32 -> IrInstruction.constInt(instruction.result().orElseThrow(), left - right);
            case MUL_I32 -> IrInstruction.constInt(instruction.result().orElseThrow(), left * right);
            case DIV_I32 -> right == 0 ? instruction : IrInstruction.constInt(instruction.result().orElseThrow(), left / right);
            default -> instruction;
        };
    }
}
