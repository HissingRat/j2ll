package xyz.melodysky.toolchain;

import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrType;

/** Structural support policy for JVM arrays, arraycopy and array allocation. */
final class NativeArrayInstructionSupport {
    boolean supportsArray(IrInstruction instruction) {
        if (instruction.opcode() == IrOpcode.ARRAY_LENGTH) {
            return referenceOperand(instruction, 1)
                    && resultIs(instruction, IrType.I32);
        }
        if (instruction.opcode() == IrOpcode.ARRAY_LOAD_I32) {
            return loadShape(instruction, IrType.I32)
                    && instruction.symbol().map(symbol ->
                            symbol.equals("int")
                                    || symbol.equals("byteOrBoolean")
                                    || symbol.equals("short")
                                    || symbol.equals("char"))
                            .orElse(false);
        }
        if (instruction.opcode() == IrOpcode.ARRAY_LOAD_I64) {
            return loadShape(instruction, IrType.I64)
                    && symbolEquals(instruction, "long");
        }
        if (instruction.opcode() == IrOpcode.ARRAY_LOAD_F32) {
            return loadShape(instruction, IrType.F32)
                    && symbolEquals(instruction, "float");
        }
        if (instruction.opcode() == IrOpcode.ARRAY_LOAD_F64) {
            return loadShape(instruction, IrType.F64)
                    && symbolEquals(instruction, "double");
        }
        if (instruction.opcode() == IrOpcode.ARRAY_STORE_I32) {
            return storeShape(instruction, IrType.I32)
                    && instruction.symbol().map(symbol ->
                            symbol.equals("int")
                                    || symbol.equals("byteOrBoolean")
                                    || symbol.equals("short")
                                    || symbol.equals("char"))
                            .orElse(false);
        }
        if (instruction.opcode() == IrOpcode.ARRAY_STORE_I64) {
            return storeShape(instruction, IrType.I64)
                    && symbolEquals(instruction, "long");
        }
        if (instruction.opcode() == IrOpcode.ARRAY_STORE_F32) {
            return storeShape(instruction, IrType.F32)
                    && symbolEquals(instruction, "float");
        }
        if (instruction.opcode() == IrOpcode.ARRAY_STORE_F64) {
            return storeShape(instruction, IrType.F64)
                    && symbolEquals(instruction, "double");
        }
        if (instruction.opcode() == IrOpcode.ARRAY_LOAD_REF) {
            return loadShape(instruction, IrType.REFERENCE);
        }
        if (instruction.opcode() == IrOpcode.ARRAY_STORE_REF) {
            return storeShape(instruction, IrType.REFERENCE);
        }
        return false;
    }

    boolean isArrayHelper(IrInstruction instruction) {
        return switch (instruction.opcode()) {
            case ARRAY_LENGTH,
                    ARRAY_LOAD_I32, ARRAY_LOAD_I64,
                    ARRAY_LOAD_F32, ARRAY_LOAD_F64,
                    ARRAY_STORE_I32, ARRAY_STORE_I64,
                    ARRAY_STORE_F32, ARRAY_STORE_F64,
                    ARRAY_LOAD_REF, ARRAY_STORE_REF -> true;
            default -> false;
        };
    }

    boolean isAllocationHelper(IrInstruction instruction) {
        return instruction.opcode() == IrOpcode.NEW_ARRAY
                || instruction.opcode() == IrOpcode.NEW_OBJECT
                || instruction.opcode() == IrOpcode.NEW_MULTI_ARRAY;
    }

    boolean supportsAllocation(IrInstruction instruction) {
        if (instruction.opcode() == IrOpcode.NEW_MULTI_ARRAY) {
            return false;
        }
        if (instruction.opcode() == IrOpcode.NEW_OBJECT) {
            return resultIs(instruction, IrType.REFERENCE)
                    && instruction.operands().isEmpty()
                    && instruction.symbol()
                            .map(symbol -> symbol.startsWith("object:"))
                            .orElse(false);
        }
        if (instruction.opcode() != IrOpcode.NEW_ARRAY) {
            return false;
        }
        return resultIs(instruction, IrType.REFERENCE)
                && instruction.operands().size() == 1
                && instruction.operands().get(0).type() == IrType.I32
                && instruction.symbol()
                        .map(symbol -> isSupportedPrimitiveArraySymbol(symbol)
                                || isSupportedReferenceArraySymbol(symbol))
                        .orElse(false);
    }

    boolean isArraycopyHelper(IrInstruction instruction) {
        return instruction.opcode() == IrOpcode.CALL_RUNTIME_HELPER
                && instruction.symbol()
                        .map(NativeRuntimeHelperSymbol::base)
                        .filter("j2ll_rt_system_arraycopy"::equals)
                        .isPresent();
    }

    boolean supportsArraycopy(IrInstruction instruction) {
        return instruction.result().isEmpty()
                && instruction.operands().size() == 5
                && instruction.operands().get(0).type() == IrType.REFERENCE
                && instruction.operands().get(1).type() == IrType.I32
                && instruction.operands().get(2).type() == IrType.REFERENCE
                && instruction.operands().get(3).type() == IrType.I32
                && instruction.operands().get(4).type() == IrType.I32;
    }

    private boolean loadShape(
            IrInstruction instruction,
            IrType resultType) {
        return instruction.operands().size() == 2
                && instruction.operands().get(0).type() == IrType.REFERENCE
                && instruction.operands().get(1).type() == IrType.I32
                && resultIs(instruction, resultType);
    }

    private boolean storeShape(
            IrInstruction instruction,
            IrType valueType) {
        return instruction.operands().size() == 3
                && instruction.operands().get(0).type() == IrType.REFERENCE
                && instruction.operands().get(1).type() == IrType.I32
                && instruction.operands().get(2).type() == valueType
                && instruction.result().isEmpty();
    }

    private boolean referenceOperand(
            IrInstruction instruction,
            int operandCount) {
        return instruction.operands().size() == operandCount
                && instruction.operands().get(0).type() == IrType.REFERENCE;
    }

    private boolean resultIs(
            IrInstruction instruction,
            IrType type) {
        return instruction.result().map(result -> result.type())
                .filter(type::equals).isPresent();
    }

    private boolean symbolEquals(
            IrInstruction instruction,
            String symbol) {
        return instruction.symbol().filter(symbol::equals).isPresent();
    }

    private boolean isSupportedPrimitiveArraySymbol(String symbol) {
        return symbol.equals("primitiveArray:boolean")
                || symbol.equals("primitiveArray:byte")
                || symbol.equals("primitiveArray:short")
                || symbol.equals("primitiveArray:char")
                || symbol.equals("primitiveArray:int")
                || symbol.equals("primitiveArray:long")
                || symbol.equals("primitiveArray:float")
                || symbol.equals("primitiveArray:double");
    }

    private boolean isSupportedReferenceArraySymbol(String symbol) {
        if (!symbol.startsWith("referenceArray:")) {
            return false;
        }
        String component = symbol.substring("referenceArray:".length());
        return !component.isEmpty()
                && (!component.startsWith("[")
                        || isValidArrayDescriptor(component));
    }

    private boolean isValidArrayDescriptor(String descriptor) {
        int componentIndex = 0;
        while (componentIndex < descriptor.length()
                && descriptor.charAt(componentIndex) == '[') {
            componentIndex++;
        }
        if (componentIndex == 0 || componentIndex >= descriptor.length()) {
            return false;
        }
        char componentType = descriptor.charAt(componentIndex);
        if ("ZBSCIJFD".indexOf(componentType) >= 0) {
            return componentIndex + 1 == descriptor.length();
        }
        return componentType == 'L'
                && componentIndex + 2 < descriptor.length()
                && descriptor.charAt(descriptor.length() - 1) == ';'
                && descriptor.indexOf(';', componentIndex + 1)
                        == descriptor.length() - 1;
    }
}
