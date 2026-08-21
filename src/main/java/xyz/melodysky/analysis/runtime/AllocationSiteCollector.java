package xyz.melodysky.analysis.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import xyz.melodysky.frontend.classfile.AsmInstructions;
import xyz.melodysky.frontend.classfile.ParsedClass;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.frontend.classfile.ParsedProgram;
import xyz.melodysky.jvm.MethodSignature;

public final class AllocationSiteCollector implements Opcodes {
    public RuntimeTypeResult collect(
            ParsedProgram program,
            Set<String> reachableMethodKeys) {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(reachableMethodKeys, "reachableMethodKeys");
        ArrayList<AllocationSite> allocationSites = new ArrayList<>();
        for (ParsedClass parsedClass : program.classes()) {
            for (ParsedMethod method : parsedClass.methods()) {
                if (!reachableMethodKeys.contains(method.methodKey())) {
                    continue;
                }
                allocationSites.addAll(collect(method));
            }
        }
        boolean conservative = allocationSites.stream().anyMatch(AllocationSite::unknown);
        return new RuntimeTypeResult(
                allocationSites.stream()
                        .flatMap(site -> site.allocatedType().stream())
                        .collect(java.util.stream.Collectors.toSet()),
                conservative,
                allocationSites);
    }

    public List<AllocationSite> collect(ParsedMethod method) {
        if (!method.hasCode()) {
            return List.of();
        }
        ArrayList<AllocationSite> allocationSites = new ArrayList<>();
        MethodSignature signature = new MethodSignature(method.name(), method.descriptor());
        int executableIndex = 0;
        for (AbstractInsnNode instruction = method.methodNode().instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!AsmInstructions.isExecutable(instruction)) {
                continue;
            }
            if (instruction instanceof TypeInsnNode typeInsn && typeInsn.getOpcode() == NEW) {
                allocationSites.add(AllocationSite.known(method.owner(), signature, executableIndex, typeInsn.desc));
            } else if (instruction instanceof TypeInsnNode typeInsn && typeInsn.getOpcode() == ANEWARRAY) {
                allocationSites.add(AllocationSite.unknown(method.owner(), signature, executableIndex));
            }
            executableIndex++;
        }
        return List.copyOf(allocationSites);
    }
}
