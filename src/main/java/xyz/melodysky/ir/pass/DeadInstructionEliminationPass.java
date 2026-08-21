package xyz.melodysky.ir.pass;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
        Map<IrValue, IrInstruction> definitions = definitions(method);
        Set<IrValue> live = liveValues(method, definitions);
        ArrayList<IrBlock> blocks = new ArrayList<>();
        for (IrBlock block : method.blocks()) {
            ArrayList<IrInstruction> kept = new ArrayList<>();
            for (IrInstruction instruction : block.instructions()) {
                if (instruction.result().isEmpty()
                        || hasSideEffect(instruction.opcode())
                        || !instruction.exceptionSites().isEmpty()
                        || live.contains(instruction.result().orElseThrow())) {
                    kept.add(instruction);
                }
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

    private Map<IrValue, IrInstruction> definitions(IrMethod method) {
        Map<IrValue, IrInstruction> definitions = new HashMap<>();
        for (IrBlock block : method.blocks()) {
            for (IrInstruction instruction : block.instructions()) {
                instruction.result().ifPresent(result -> definitions.put(result, instruction));
            }
        }
        return definitions;
    }

    private Set<IrValue> liveValues(
            IrMethod method,
            Map<IrValue, IrInstruction> definitions) {
        Set<IrValue> live = new HashSet<>();
        ArrayDeque<IrValue> work = new ArrayDeque<>();
        for (IrBlock block : method.blocks()) {
            block.terminator().value().ifPresent(work::add);
            block.terminator().condition().ifPresent(work::add);
            block.terminator().switchValue().ifPresent(work::add);
            work.addAll(block.terminator().targetArguments());
            work.addAll(block.terminator().trueTargetArguments());
            work.addAll(block.terminator().falseTargetArguments());
            work.addAll(block.terminator().defaultTargetArguments());
            for (var switchCase : block.terminator().switchCases()) {
                work.addAll(switchCase.arguments());
            }
            for (var edge : block.exceptionEdges()) {
                work.addAll(edge.arguments());
            }
            for (IrInstruction instruction : block.instructions()) {
                for (var site : instruction.exceptionSites()) {
                    for (var edge : site.handlers()) {
                        work.addAll(edge.arguments());
                    }
                }
                if (instruction.result().isEmpty()
                        || hasSideEffect(instruction.opcode())
                        || !instruction.exceptionSites().isEmpty()) {
                    work.addAll(instruction.operands());
                }
            }
        }
        while (!work.isEmpty()) {
            IrValue value = work.removeFirst();
            if (!live.add(value)) {
                continue;
            }
            IrInstruction definition = definitions.get(value);
            if (definition != null) {
                work.addAll(definition.operands());
            }
        }
        return live;
    }

    private boolean hasSideEffect(IrOpcode opcode) {
        return switch (opcode) {
            case NEW_OBJECT, NEW_ARRAY, NEW_MULTI_ARRAY,
                    ARRAY_STORE_I32, ARRAY_STORE_I64, ARRAY_STORE_F32, ARRAY_STORE_F64, ARRAY_STORE_REF,
                    PUT_STATIC, PUT_FIELD,
                    CALL_STATIC, CALL_SPECIAL, CALL_DIRECT, CALL_VIRTUAL, CALL_INTERFACE, CALL_DYNAMIC, CALL_RUNTIME_HELPER,
                    MONITOR_ENTER, MONITOR_EXIT, MONITOR_EXIT_ON_EXCEPTION,
                    CLASS_INIT_GUARD, CLASS_INIT_BEGIN, CLASS_INIT_END, CLASS_INIT_FAILED,
                    CLASS_INIT_HAPPENS_BEFORE, CLASS_INIT_ACTIVE_USE,
                    VOLATILE_READ_BARRIER, VOLATILE_WRITE_BARRIER, FINAL_FIELD_PUBLICATION,
                    MONITOR_HAPPENS_BEFORE, THREAD_START_HAPPENS_BEFORE, THREAD_JOIN_HAPPENS_BEFORE -> true;
            default -> false;
        };
    }
}
