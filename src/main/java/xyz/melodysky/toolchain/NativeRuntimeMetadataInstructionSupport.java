package xyz.melodysky.toolchain;

import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrType;

/** Structural support policy for closed reflection/runtime-metadata helpers. */
final class NativeRuntimeMetadataInstructionSupport {
    boolean isHelper(IrInstruction instruction) {
        if (instruction.opcode() != IrOpcode.CALL_RUNTIME_HELPER) {
            return false;
        }
        return instruction.symbol().map(NativeRuntimeHelperSymbol::base)
                .map(base -> base.equals("j2ll_rt_class_for_name_static")
                        || base.equals("j2ll_rt_get_declared_method")
                        || base.equals("j2ll_rt_get_declared_field")
                        || base.equals("j2ll_rt_get_declared_constructor")
                        || base.equals("j2ll_rt_reflect_invoke")
                        || base.equals("j2ll_rt_reflect_new_instance")
                        || base.equals("j2ll_rt_reflect_set_accessible")
                        || base.startsWith("j2ll_rt_reflect_field_"))
                .orElse(false);
    }

    boolean supports(IrInstruction instruction) {
        String symbol = NativeRuntimeHelperSymbol.base(
                instruction.symbol().orElseThrow());
        if (symbol.equals("j2ll_rt_class_for_name_static")) {
            return resultIs(instruction, IrType.REFERENCE)
                    && operandsAre(instruction, IrType.I64, IrType.I32)
                    && NativeRuntimeHelperSymbol.metadataKey(instruction)
                            .map(key -> key.startsWith("class:"))
                            .orElse(false);
        }
        if (symbol.equals("j2ll_rt_get_declared_field")) {
            return resultIs(instruction, IrType.REFERENCE)
                    && operandsAre(instruction, IrType.I64)
                    && NativeRuntimeHelperSymbol.metadataKey(instruction)
                            .map(key -> key.startsWith("field:"))
                            .orElse(false);
        }
        if (symbol.equals("j2ll_rt_get_declared_method")
                || symbol.equals("j2ll_rt_get_declared_constructor")) {
            return resultIs(instruction, IrType.REFERENCE)
                    && operandsAre(instruction, IrType.I64)
                    && NativeRuntimeHelperSymbol.metadataKey(instruction)
                            .map(this::validMemberKey)
                            .orElse(false);
        }
        if (symbol.equals("j2ll_rt_reflect_invoke")) {
            return resultIs(instruction, IrType.REFERENCE)
                    && operandsAre(
                            instruction,
                            IrType.REFERENCE,
                            IrType.REFERENCE,
                            IrType.REFERENCE);
        }
        if (symbol.equals("j2ll_rt_reflect_new_instance")) {
            return resultIs(instruction, IrType.REFERENCE)
                    && operandsAre(
                            instruction,
                            IrType.REFERENCE,
                            IrType.REFERENCE);
        }
        if (symbol.equals("j2ll_rt_reflect_set_accessible")) {
            return instruction.result().isEmpty()
                    && operandsAre(
                            instruction,
                            IrType.REFERENCE,
                            IrType.I32);
        }
        if (symbol.equals("j2ll_rt_reflect_field_get")) {
            return fieldGet(instruction, IrType.REFERENCE);
        }
        if (symbol.equals("j2ll_rt_reflect_field_set")) {
            return fieldSet(instruction, IrType.REFERENCE);
        }
        if (symbol.equals("j2ll_rt_reflect_field_get_int")
                || symbol.equals("j2ll_rt_reflect_field_get_boolean")) {
            return fieldGet(instruction, IrType.I32);
        }
        if (symbol.equals("j2ll_rt_reflect_field_set_int")
                || symbol.equals("j2ll_rt_reflect_field_set_boolean")) {
            return fieldSet(instruction, IrType.I32);
        }
        if (symbol.equals("j2ll_rt_reflect_field_get_long")) {
            return fieldGet(instruction, IrType.I64);
        }
        if (symbol.equals("j2ll_rt_reflect_field_set_long")) {
            return fieldSet(instruction, IrType.I64);
        }
        if (symbol.equals("j2ll_rt_reflect_field_get_double")) {
            return fieldGet(instruction, IrType.F64);
        }
        if (symbol.equals("j2ll_rt_reflect_field_set_double")) {
            return fieldSet(instruction, IrType.F64);
        }
        return false;
    }

    private boolean validMemberKey(String key) {
        if (key.startsWith("method:")) {
            int descriptorStart = key.indexOf('!');
            return descriptorStart >= 0
                    && key.substring(descriptorStart + 1).startsWith("(");
        }
        if (key.startsWith("constructor:")) {
            int descriptorStart = key.indexOf('!');
            return descriptorStart >= 0
                    && key.substring(descriptorStart + 1).startsWith("(")
                    && key.substring(descriptorStart + 1).endsWith("V");
        }
        return false;
    }

    private boolean fieldGet(
            IrInstruction instruction,
            IrType resultType) {
        return resultIs(instruction, resultType)
                && operandsAre(
                        instruction,
                        IrType.REFERENCE,
                        IrType.REFERENCE);
    }

    private boolean fieldSet(
            IrInstruction instruction,
            IrType valueType) {
        return instruction.result().isEmpty()
                && operandsAre(
                        instruction,
                        IrType.REFERENCE,
                        IrType.REFERENCE,
                        valueType);
    }

    private boolean resultIs(
            IrInstruction instruction,
            IrType type) {
        return instruction.result().map(result -> result.type())
                .filter(type::equals).isPresent();
    }

    private boolean operandsAre(
            IrInstruction instruction,
            IrType... types) {
        if (instruction.operands().size() != types.length) {
            return false;
        }
        for (int index = 0; index < types.length; index++) {
            if (instruction.operands().get(index).type() != types[index]) {
                return false;
            }
        }
        return true;
    }
}
