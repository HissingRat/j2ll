package xyz.melodysky.toolchain;

import java.util.Locale;
import java.util.Set;

/** Canonicalizes assembly operands while retaining width and addressing shape. */
final class NativeRegistrationAssemblyOperandShape {
    private static final Set<String> X64_64 = Set.of(
            "rax", "rbx", "rcx", "rdx", "rsi", "rdi", "rsp", "rbp");
    private static final Set<String> X64_32 = Set.of(
            "eax", "ebx", "ecx", "edx", "esi", "edi", "esp", "ebp");
    private static final Set<String> X64_16 = Set.of(
            "ax", "bx", "cx", "dx", "si", "di", "sp", "bp");
    private static final Set<String> X64_8 = Set.of(
            "al", "bl", "cl", "dl", "sil", "dil", "spl", "bpl");

    private final NativeRegistrationAssemblyInstructionSet instructions;
    private final boolean x64;

    NativeRegistrationAssemblyOperandShape(
            NativeRegistrationAssemblyInstructionSet instructions,
            boolean x64) {
        this.instructions = instructions;
        this.x64 = x64;
    }

    String normalize(NativeRegistrationAssemblyIndex.Instruction instruction) {
        String operands = instruction.operands();
        boolean structuralImmediate = isShiftOrRotate(instruction.mnemonic());
        StringBuilder normalized = new StringBuilder();
        int squareDepth = 0;
        int cursor = 0;
        while (cursor < operands.length()) {
            char ch = operands.charAt(cursor);
            if (ch == '[') {
                squareDepth++;
            } else if (ch == ']') {
                squareDepth = Math.max(0, squareDepth - 1);
            }
            if ((ch == '$' || ch == '#') && cursor + 1 < operands.length()) {
                int end = numberEnd(operands, cursor + 1);
                if (end > cursor + 1) {
                    String number = operands.substring(cursor + 1, end);
                    normalized.append(ch).append(
                            squareDepth > 0
                                    ? "disp"
                                    : structuralImmediate || isZeroOrOne(number)
                                            ? number.toLowerCase(Locale.ROOT)
                                            : "imm");
                    cursor = end;
                    continue;
                }
            }
            if ((Character.isDigit(ch)
                    || (ch == '-' && cursor + 1 < operands.length()
                            && Character.isDigit(operands.charAt(cursor + 1))))) {
                int end = numberEnd(operands, cursor);
                if (end > cursor && nextNonSpace(operands, end) == '(') {
                    normalized.append("disp");
                    cursor = end;
                    continue;
                }
            }
            if (ch == '%') {
                int end = identifierEnd(operands, cursor + 1);
                normalized.append('%').append(registerClass(
                        operands.substring(cursor + 1, end)));
                cursor = end;
                continue;
            }
            if (Character.isLetter(ch) || ch == '_' || ch == '.') {
                int end = identifierEnd(operands, cursor);
                String token = operands.substring(cursor, end);
                normalized.append(isLocalLabel(token) ? "local" : registerClass(token));
                cursor = end;
                continue;
            }
            if (!Character.isWhitespace(ch)
                    || normalized.length() == 0
                    || normalized.charAt(normalized.length() - 1) != ' ') {
                normalized.append(Character.isWhitespace(ch) ? ' ' : ch);
            }
            cursor++;
        }
        return normalized.toString().trim();
    }

    void collectWidths(
            NativeRegistrationAssemblyIndex.Instruction instruction,
            Set<Integer> widths) {
        String mnemonic = instruction.mnemonic();
        if (x64 && (mnemonic.equals("cltq") || mnemonic.equals("cdqe"))) {
            widths.add(32);
            widths.add(64);
        }
        if (mnemonic.contains("zbl") || mnemonic.contains("sbl")) widths.add(8);
        if (mnemonic.contains("zwl") || mnemonic.contains("swl")
                || mnemonic.contains("zwq") || mnemonic.contains("swq")) widths.add(16);
        if (!x64 && mnemonic.endsWith("b")
                && (mnemonic.startsWith("ldr") || mnemonic.startsWith("str"))) widths.add(8);
        if (!x64 && mnemonic.endsWith("h")
                && (mnemonic.startsWith("ldr") || mnemonic.startsWith("str"))) widths.add(16);
        for (String token : instructions.identifierTokens(instruction.operands())) {
            int width = registerWidth(token);
            if (width != 0) widths.add(width);
        }
        if (x64 && (instruction.operands().contains("(")
                || instruction.operands().contains("["))) {
            char suffix = mnemonic.charAt(mnemonic.length() - 1);
            if (suffix == 'b') widths.add(8);
            if (suffix == 'w') widths.add(16);
            if (suffix == 'l') widths.add(32);
            if (suffix == 'q') widths.add(64);
        }
    }

    boolean shiftOrRotate(NativeRegistrationAssemblyIndex.Instruction instruction) {
        return isShiftOrRotate(instruction.mnemonic())
                || !x64 && hasShiftedOperand(instruction.operands());
    }

    private String registerClass(String token) {
        int width = registerWidth(token);
        if (width == 0) return token.toLowerCase(Locale.ROOT);
        if (token.equalsIgnoreCase("rsp") || token.equalsIgnoreCase("sp")) return "sp" + width;
        if (token.equalsIgnoreCase("rbp")) return "bp" + width;
        return "r" + width;
    }

    private int registerWidth(String token) {
        String value = token.startsWith("%") ? token.substring(1) : token;
        value = value.toLowerCase(Locale.ROOT);
        if (isNumberedRegister(value, 'x', "")) return 64;
        if (isNumberedRegister(value, 'w', "") || value.equals("wsp")) return 32;
        if (value.equals("sp")) return 64;
        if (isNumberedRegister(value, 'r', "")) return 64;
        if (isNumberedRegister(value, 'r', "d")) return 32;
        if (isNumberedRegister(value, 'r', "w")) return 16;
        if (isNumberedRegister(value, 'r', "b")) return 8;
        if (X64_64.contains(value)) return 64;
        if (X64_32.contains(value)) return 32;
        if (X64_16.contains(value)) return 16;
        if (X64_8.contains(value)) return 8;
        return 0;
    }

    private boolean isNumberedRegister(String value, char prefix, String suffix) {
        if (!value.startsWith(String.valueOf(prefix)) || !value.endsWith(suffix)) return false;
        int end = value.length() - suffix.length();
        if (end <= 1) return false;
        try {
            int number = Integer.parseInt(value.substring(1, end));
            return prefix == 'r' ? number <= 15 : number <= 31;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private boolean isShiftOrRotate(String mnemonic) {
        return mnemonic.startsWith("shl") || mnemonic.startsWith("shr")
                || mnemonic.startsWith("sar") || mnemonic.equals("lsl")
                || mnemonic.equals("lsr") || mnemonic.equals("asr")
                || mnemonic.startsWith("ror") || mnemonic.startsWith("rol")
                || mnemonic.equals("extr");
    }

    private boolean hasShiftedOperand(String operands) {
        for (String token : instructions.identifierTokens(operands)) {
            String normalized = token.toLowerCase(Locale.ROOT);
            if (normalized.equals("lsl") || normalized.equals("lsr")
                    || normalized.equals("asr") || normalized.equals("ror")) {
                return true;
            }
        }
        return false;
    }

    private int identifierEnd(String value, int start) {
        int end = start;
        while (end < value.length()) {
            char ch = value.charAt(end);
            if (!Character.isLetterOrDigit(ch)
                    && ch != '_' && ch != '.' && ch != '$' && ch != '@') break;
            end++;
        }
        return end;
    }

    private int numberEnd(String value, int start) {
        int end = start;
        if (end < value.length() && (value.charAt(end) == '+' || value.charAt(end) == '-')) end++;
        while (end < value.length()) {
            char ch = Character.toLowerCase(value.charAt(end));
            if (!Character.isDigit(ch) && ch != 'x' && (ch < 'a' || ch > 'f')) break;
            end++;
        }
        return end;
    }

    private char nextNonSpace(String value, int start) {
        int cursor = start;
        while (cursor < value.length() && Character.isWhitespace(value.charAt(cursor))) cursor++;
        return cursor == value.length() ? '\0' : value.charAt(cursor);
    }

    private boolean isZeroOrOne(String value) {
        try {
            String unsigned = value.startsWith("+") || value.startsWith("-")
                    ? value.substring(1) : value;
            long parsed = unsigned.startsWith("0x") || unsigned.startsWith("0X")
                    ? Long.parseUnsignedLong(unsigned.substring(2), 16)
                    : Long.parseUnsignedLong(unsigned, 10);
            return parsed <= 1;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private boolean isLocalLabel(String token) {
        return token.startsWith(".L") || token.startsWith("LBB") || token.startsWith("Ltmp");
    }
}
