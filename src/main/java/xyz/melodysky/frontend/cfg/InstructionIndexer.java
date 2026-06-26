package xyz.melodysky.frontend.cfg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import xyz.melodysky.frontend.classfile.AsmInstructions;

final class InstructionIndexer {
    private final List<AbstractInsnNode> executableInstructions;
    private final Map<AbstractInsnNode, Integer> indexes;

    private InstructionIndexer(List<AbstractInsnNode> executableInstructions, Map<AbstractInsnNode, Integer> indexes) {
        this.executableInstructions = executableInstructions;
        this.indexes = indexes;
    }

    static InstructionIndexer from(InsnList instructions) {
        ArrayList<AbstractInsnNode> executable = new ArrayList<>();
        HashMap<AbstractInsnNode, Integer> indexes = new HashMap<>();
        for (AbstractInsnNode instruction = instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
            if (!AsmInstructions.isExecutable(instruction)) {
                continue;
            }
            indexes.put(instruction, executable.size());
            executable.add(instruction);
        }
        return new InstructionIndexer(List.copyOf(executable), Map.copyOf(indexes));
    }

    List<AbstractInsnNode> executableInstructions() {
        return executableInstructions;
    }

    Integer indexOf(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction;
        while (current != null && !AsmInstructions.isExecutable(current)) {
            current = current.getNext();
        }
        return current == null ? null : indexes.get(current);
    }
}
