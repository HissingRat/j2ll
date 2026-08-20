package xyz.melodysky.toolchain;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Architecture-aware control-instruction classification for the registration gate. */
final class NativeRegistrationAssemblyInstructionSet {
    private final NativeRegistrationAssemblyIndex index;
    private final boolean x64;

    NativeRegistrationAssemblyInstructionSet(NativeRegistrationAssemblyIndex index) {
        this.index = index;
        this.x64 = index.archClassifier().equals("x64");
    }

    boolean isCall(NativeRegistrationAssemblyIndex.Instruction instruction) {
        return x64
                ? instruction.mnemonic().equals("call")
                        || instruction.mnemonic().equals("callq")
                : instruction.mnemonic().equals("bl");
    }

    boolean isIndirectCall(NativeRegistrationAssemblyIndex.Instruction instruction) {
        return !x64
                && (instruction.mnemonic().equals("blr")
                        || instruction.mnemonic().startsWith("blra"));
    }

    boolean isUnconditionalBranch(NativeRegistrationAssemblyIndex.Instruction instruction) {
        return x64
                ? instruction.mnemonic().equals("jmp")
                        || instruction.mnemonic().equals("jmpq")
                : instruction.mnemonic().equals("b");
    }

    boolean isIndirectBranch(NativeRegistrationAssemblyIndex.Instruction instruction) {
        if (x64) {
            return isUnconditionalBranch(instruction)
                    && instruction.operands().stripLeading().startsWith("*");
        }
        return instruction.mnemonic().equals("br")
                || instruction.mnemonic().startsWith("bra");
    }

    boolean isConditionalBranch(NativeRegistrationAssemblyIndex.Instruction instruction) {
        if (x64) {
            return instruction.mnemonic().startsWith("j")
                    && !instruction.mnemonic().equals("jmp")
                    && !instruction.mnemonic().equals("jmpq");
        }
        return instruction.mnemonic().startsWith("b.")
                || instruction.mnemonic().equals("cbz")
                || instruction.mnemonic().equals("cbnz")
                || instruction.mnemonic().equals("tbz")
                || instruction.mnemonic().equals("tbnz");
    }

    boolean isReturn(NativeRegistrationAssemblyIndex.Instruction instruction) {
        return x64
                ? instruction.mnemonic().equals("ret")
                        || instruction.mnemonic().equals("retq")
                : instruction.mnemonic().equals("ret");
    }

    String directTarget(NativeRegistrationAssemblyIndex.Instruction instruction) {
        return targetFromOperand(instruction.operands());
    }

    String conditionalTarget(NativeRegistrationAssemblyIndex.Instruction instruction) {
        if (x64 || instruction.mnemonic().startsWith("b.")) {
            return directTarget(instruction);
        }
        int comma = instruction.operands().lastIndexOf(',');
        return comma < 0
                ? null
                : targetFromOperand(instruction.operands().substring(comma + 1));
    }

    boolean isLocalTarget(
            NativeRegistrationAssemblyIndex.Function function,
            String target) {
        Integer targetIndex = localTargetIndex(function, target);
        return targetIndex != null
                && targetIndex >= 0
                && targetIndex < function.instructions().size();
    }

    Integer localTargetIndex(
            NativeRegistrationAssemblyIndex.Function function,
            String target) {
        return target == null
                ? null
                : function.labelInstructionIndexes().get(target);
    }

    List<String> identifierTokens(String operands) {
        ArrayList<String> result = new ArrayList<>();
        int cursor = 0;
        while (cursor < operands.length()) {
            char first = operands.charAt(cursor);
            if (!Character.isLetter(first) && first != '_' && first != '.') {
                cursor++;
                continue;
            }
            int end = identifierEnd(operands, cursor);
            result.add(operands.substring(cursor, end));
            cursor = end;
        }
        return result;
    }

    ContinuationProfile continuationProfile(
            NativeRegistrationAssemblyIndex.Function function,
            Set<Integer> reachableInstructionIndexes) {
        return new NativeRegistrationAssemblyContinuationProfiler(this, x64)
                .profile(function, reachableInstructionIndexes);
    }

    private String targetFromOperand(String rawOperand) {
        String operand = rawOperand.stripLeading();
        if (operand.isEmpty()
                || operand.startsWith("*")
                || operand.startsWith("%")
                || operand.startsWith("[")) {
            return null;
        }
        int end = 0;
        while (end < operand.length()
                && !Character.isWhitespace(operand.charAt(end))
                && operand.charAt(end) != ',') {
            end++;
        }
        String token = operand.substring(0, end);
        if (token.isEmpty()) {
            return null;
        }
        for (int cursor = 0; cursor < token.length(); cursor++) {
            char ch = token.charAt(cursor);
            if (!Character.isLetterOrDigit(ch)
                    && ch != '_'
                    && ch != '.'
                    && ch != '$'
                    && ch != '@') {
                return null;
            }
        }
        return index.canonicalSymbol(token);
    }

    private int identifierEnd(String value, int start) {
        int end = start;
        while (end < value.length()) {
            char ch = value.charAt(end);
            if (!Character.isLetterOrDigit(ch)
                    && ch != '_'
                    && ch != '.'
                    && ch != '$'
                    && ch != '@') {
                break;
            }
            end++;
        }
        return end;
    }

    record ContinuationProfile(
            List<String> signature,
            Set<Integer> widths,
            int memoryOperations,
            boolean bitOr,
            boolean shiftOrRotate) {}
}
