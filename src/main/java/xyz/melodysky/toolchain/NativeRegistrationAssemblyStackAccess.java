package xyz.melodysky.toolchain;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Classifies stack-local accesses and stack/frame-base mutations. */
final class NativeRegistrationAssemblyStackAccess {
    enum Base {
        STACK_POINTER,
        FRAME_POINTER
    }

    record Slot(Base base, String address) {}

    private static final Set<String> X64_READ_MODIFY_PREFIXES = Set.of(
            "add", "sub", "xor", "or", "and", "shl", "shr", "sar",
            "rol", "ror", "inc", "dec");
    private static final Set<String> A64_READ_ONLY_PREFIXES = Set.of(
            "cmp", "cmn", "tst", "ccmp", "ccmn", "fcmp", "fcmpe",
            "prfm", "msr");

    private final boolean x64;

    NativeRegistrationAssemblyStackAccess(boolean x64) {
        this.x64 = x64;
    }

    Slot writtenStackSlot(NativeRegistrationAssemblyIndex.Instruction instruction) {
        List<String> operands = operands(instruction.operands());
        if (operands.isEmpty()) {
            return null;
        }
        String mnemonic = instruction.mnemonic();
        if (x64) {
            String destination = operands.get(operands.size() - 1);
            return mnemonic.startsWith("mov") || isX64ReadModify(mnemonic)
                    ? stackSlot(destination)
                    : null;
        }
        return mnemonic.startsWith("str") || mnemonic.startsWith("stur")
                        || mnemonic.startsWith("stp")
                ? stackSlot(memoryOperand(operands))
                : null;
    }

    Slot readStackSlot(NativeRegistrationAssemblyIndex.Instruction instruction) {
        List<String> operands = operands(instruction.operands());
        if (operands.isEmpty()) {
            return null;
        }
        String mnemonic = instruction.mnemonic();
        if (x64) {
            if (isX64ReadModify(mnemonic)) {
                return stackSlot(operands.get(operands.size() - 1));
            }
            return mnemonic.startsWith("mov") ? stackSlot(operands.get(0)) : null;
        }
        return mnemonic.startsWith("ldr") || mnemonic.startsWith("ldur")
                        || mnemonic.startsWith("ldp")
                ? stackSlot(memoryOperand(operands))
                : null;
    }

    Set<Base> mutatedBases(NativeRegistrationAssemblyIndex.Instruction instruction) {
        return x64 ? x64MutatedBases(instruction) : a64MutatedBases(instruction);
    }

    private Set<Base> x64MutatedBases(
            NativeRegistrationAssemblyIndex.Instruction instruction) {
        String mnemonic = instruction.mnemonic();
        EnumSet<Base> result = EnumSet.noneOf(Base.class);
        boolean push = isX64Push(mnemonic);
        boolean pop = isX64Pop(mnemonic);
        if (push || pop || mnemonic.startsWith("iret")) {
            result.add(Base.STACK_POINTER);
        }
        if (mnemonic.startsWith("enter") || mnemonic.startsWith("leave")) {
            result.add(Base.STACK_POINTER);
            result.add(Base.FRAME_POINTER);
        }
        List<String> operands = operands(instruction.operands());
        if (push) {
            return Set.copyOf(result);
        }
        if (mnemonic.startsWith("xchg") || mnemonic.startsWith("xadd")) {
            operands.forEach(operand -> addRegisterBase(result, operand));
        } else if (!isX64ReadOnly(mnemonic)
                && !operands.isEmpty()) {
            addRegisterBase(result, operands.get(operands.size() - 1));
        }
        return Set.copyOf(result);
    }

    private Set<Base> a64MutatedBases(
            NativeRegistrationAssemblyIndex.Instruction instruction) {
        String mnemonic = instruction.mnemonic();
        List<String> operands = operands(instruction.operands());
        EnumSet<Base> result = EnumSet.noneOf(Base.class);
        if (isA64MemoryInstruction(mnemonic)) {
            addA64WritebackBase(result, operands);
            if (mnemonic.startsWith("ld") && !operands.isEmpty()) {
                addRegisterBase(result, operands.get(0));
                if (mnemonic.startsWith("ldp") && operands.size() > 1) {
                    addRegisterBase(result, operands.get(1));
                }
            }
            return Set.copyOf(result);
        }
        if (!startsWithAny(mnemonic, A64_READ_ONLY_PREFIXES)
                && !operands.isEmpty()) {
            addRegisterBase(result, operands.get(0));
        }
        return Set.copyOf(result);
    }

    private void addA64WritebackBase(EnumSet<Base> result, List<String> operands) {
        for (int index = 0; index < operands.size(); index++) {
            String operand = compact(operands.get(index));
            Slot slot = stackSlot(operand);
            if (slot == null) {
                continue;
            }
            boolean preIndexed = operand.endsWith("]!");
            boolean postIndexed = operand.endsWith("]") && index + 1 < operands.size();
            if (preIndexed || postIndexed) {
                result.add(slot.base());
            }
        }
    }

    private boolean isA64MemoryInstruction(String mnemonic) {
        return mnemonic.startsWith("ld") || mnemonic.startsWith("st")
                || mnemonic.startsWith("prfm");
    }

    private Slot stackSlot(String operand) {
        String compact = compact(operand);
        if (x64) {
            int open = compact.indexOf('(');
            int close = compact.indexOf(')', open + 1);
            if (open < 0 || close < 0) {
                return null;
            }
            String address = compact.substring(0, close + 1);
            Base base = x64AddressBase(address);
            return base == null ? null : new Slot(base, address);
        }
        int open = compact.indexOf('[');
        int close = compact.indexOf(']', open + 1);
        if (open < 0 || close < 0) {
            return null;
        }
        String address = compact.substring(open, close + 1);
        String baseToken = address.substring(1, address.length() - 1).split(",", 2)[0];
        Base base = registerBase(baseToken);
        if (base == null) {
            return null;
        }
        if (base == Base.FRAME_POINTER && address.startsWith("[fp")) {
            address = "[x29" + address.substring(3);
        }
        return new Slot(base, address);
    }

    private Base x64AddressBase(String address) {
        int open = address.indexOf('(');
        int close = address.lastIndexOf(')');
        String[] registers = address.substring(open + 1, close).split(",");
        for (String register : registers) {
            Base base = registerBase(register);
            if (base != null) {
                return base;
            }
        }
        return null;
    }

    private void addRegisterBase(EnumSet<Base> result, String operand) {
        Base base = registerBase(compact(operand));
        if (base != null) {
            result.add(base);
        }
    }

    private Base registerBase(String raw) {
        String value = compact(raw);
        if (value.startsWith("%")) {
            value = value.substring(1);
        }
        return switch (value) {
            case "rsp", "esp", "sp", "spl", "wsp" -> Base.STACK_POINTER;
            case "rbp", "ebp", "bp", "bpl", "x29", "w29", "fp" ->
                    Base.FRAME_POINTER;
            default -> null;
        };
    }

    private String memoryOperand(List<String> operands) {
        for (String operand : operands) {
            if (operand.contains("[")) {
                return operand;
            }
        }
        return "";
    }

    private boolean isX64ReadModify(String mnemonic) {
        return startsWithAny(mnemonic, X64_READ_MODIFY_PREFIXES);
    }

    private boolean isX64ReadOnly(String mnemonic) {
        boolean compare = mnemonic.startsWith("cmp")
                && !mnemonic.startsWith("cmpxchg");
        boolean bitTest = mnemonic.equals("bt")
                || mnemonic.equals("btq")
                || mnemonic.equals("btl")
                || mnemonic.equals("btw");
        return compare || bitTest || mnemonic.startsWith("test")
                || mnemonic.startsWith("ucomi") || mnemonic.startsWith("comi")
                || mnemonic.startsWith("prefetch");
    }

    private boolean isX64Push(String mnemonic) {
        return mnemonic.equals("push") || mnemonic.equals("pushq")
                || mnemonic.equals("pushl") || mnemonic.equals("pushw")
                || mnemonic.startsWith("pushf");
    }

    private boolean isX64Pop(String mnemonic) {
        return mnemonic.equals("pop") || mnemonic.equals("popq")
                || mnemonic.equals("popl") || mnemonic.equals("popw")
                || mnemonic.startsWith("popf");
    }

    private boolean startsWithAny(String value, String... prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean startsWithAny(String value, Set<String> prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private List<String> operands(String raw) {
        ArrayList<String> result = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int index = 0; index < raw.length(); index++) {
            char ch = raw.charAt(index);
            if (ch == '(' || ch == '[') depth++;
            if (ch == ')' || ch == ']') depth--;
            if (ch == ',' && depth == 0) {
                result.add(raw.substring(start, index).trim());
                start = index + 1;
            }
        }
        if (start < raw.length()) {
            result.add(raw.substring(start).trim());
        }
        return List.copyOf(result);
    }

    private String compact(String value) {
        return value.toLowerCase(Locale.ROOT).replace(" ", "").replace("\t", "");
    }
}
