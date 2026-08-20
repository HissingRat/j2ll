package xyz.melodysky.toolchain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Builds salt-normalized, width-sensitive post-call structural signatures. */
final class NativeRegistrationAssemblyContinuationProfiler {
    private final NativeRegistrationAssemblyInstructionSet instructions;
    private final boolean x64;
    private final NativeRegistrationAssemblyOperandShape operandShape;

    NativeRegistrationAssemblyContinuationProfiler(
            NativeRegistrationAssemblyInstructionSet instructions,
            boolean x64) {
        this.instructions = instructions;
        this.x64 = x64;
        this.operandShape = new NativeRegistrationAssemblyOperandShape(instructions, x64);
    }

    NativeRegistrationAssemblyInstructionSet.ContinuationProfile profile(
            NativeRegistrationAssemblyIndex.Function function,
            Set<Integer> reachableInstructionIndexes) {
        ArrayList<String> signature = new ArrayList<>();
        HashSet<Integer> widths = new HashSet<>();
        int memoryOperations = 0;
        boolean bitOr = false;
        boolean shiftOrRotate = false;
        ArrayList<Integer> ordered = new ArrayList<>(reachableInstructionIndexes);
        ordered.sort(Integer::compareTo);
        for (int cursor : ordered) {
            NativeRegistrationAssemblyIndex.Instruction instruction =
                    function.instructions().get(cursor);
            if (instructions.isReturn(instruction)) {
                continue;
            }
            signature.add(instruction.mnemonic() + " "
                    + operandShape.normalize(instruction)
                    + edgeShape(function, instruction, cursor, ordered));
            operandShape.collectWidths(instruction, widths);
            if (isMemoryOperation(instruction)) {
                memoryOperations++;
            }
            String mnemonic = instruction.mnemonic();
            bitOr |= mnemonic.equals("or")
                    || mnemonic.startsWith("orq")
                    || mnemonic.startsWith("orl")
                    || mnemonic.equals("orr");
            shiftOrRotate |= operandShape.shiftOrRotate(instruction);
        }
        return new NativeRegistrationAssemblyInstructionSet.ContinuationProfile(
                List.copyOf(signature),
                Set.copyOf(widths),
                memoryOperations,
                bitOr,
                shiftOrRotate);
    }

    private String edgeShape(
            NativeRegistrationAssemblyIndex.Function function,
            NativeRegistrationAssemblyIndex.Instruction instruction,
            int instructionIndex,
            List<Integer> ordered) {
        if (instructions.isUnconditionalBranch(instruction)) {
            return " ->u" + rank(
                    ordered,
                    instructions.localTargetIndex(
                            function,
                            instructions.directTarget(instruction)));
        }
        if (instructions.isConditionalBranch(instruction)) {
            return " ->c" + rank(ordered, instructionIndex + 1)
                    + "," + rank(
                            ordered,
                            instructions.localTargetIndex(
                                    function,
                                    instructions.conditionalTarget(instruction)));
        }
        return "";
    }

    private int rank(List<Integer> ordered, Integer instructionIndex) {
        return instructionIndex == null ? -1 : ordered.indexOf(instructionIndex);
    }

    private boolean isMemoryOperation(
            NativeRegistrationAssemblyIndex.Instruction instruction) {
        String operands = instruction.operands();
        if (x64) {
            return (operands.contains("(") || operands.contains("["))
                    && !instruction.mnemonic().startsWith("lea")
                    && !instructions.isCall(instruction)
                    && !instructions.isConditionalBranch(instruction)
                    && !instructions.isUnconditionalBranch(instruction);
        }
        String mnemonic = instruction.mnemonic();
        return operands.contains("[")
                && (mnemonic.startsWith("ldr") || mnemonic.startsWith("ldur")
                        || mnemonic.startsWith("ldp") || mnemonic.startsWith("str")
                        || mnemonic.startsWith("stur") || mnemonic.startsWith("stp"));
    }

}
