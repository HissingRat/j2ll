package xyz.melodysky.ir.pass;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
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
                        || hasSideEffect(instruction.opcode())
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

    private boolean hasSideEffect(IrOpcode opcode) {
        return switch (opcode) {
            case NEW_OBJECT, NEW_ARRAY, NEW_MULTI_ARRAY,
                    ARRAY_STORE_I32, ARRAY_STORE_I64, ARRAY_STORE_F32, ARRAY_STORE_F64, ARRAY_STORE_REF,
                    PUT_STATIC, PUT_FIELD,
                    CALL_STATIC, CALL_SPECIAL, CALL_VIRTUAL, CALL_INTERFACE, CALL_DYNAMIC, CALL_RUNTIME_HELPER,
                    MONITOR_ENTER, MONITOR_EXIT, MONITOR_EXIT_ON_EXCEPTION,
                    CLASS_INIT_GUARD, CLASS_INIT_BEGIN, CLASS_INIT_END, CLASS_INIT_FAILED, CLASS_INIT_HAPPENS_BEFORE,
                    VOLATILE_READ_BARRIER, VOLATILE_WRITE_BARRIER, FINAL_FIELD_PUBLICATION,
                    MONITOR_HAPPENS_BEFORE, THREAD_START_HAPPENS_BEFORE, THREAD_JOIN_HAPPENS_BEFORE -> true;
            default -> false;
        };
    }
}
