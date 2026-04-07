package xyz.melodysky.ir.validate;

import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class IrMethodValidator {

    public void validate(IrMethod method) {
        Map<String, IrBlock> blockByLabel = new HashMap<>();
        for (IrBlock block : method.blocks()) {
            blockByLabel.put(block.label(), block);
        }

        Set<Integer> producedValueIds = new HashSet<>();
        for (IrBlock block : method.blocks()) {
            validateBlock(method, block, blockByLabel, producedValueIds);
        }
    }

    private void validateBlock(IrMethod method, IrBlock block, Map<String, IrBlock> blockByLabel, Set<Integer> producedValueIds) {
        Set<Integer> availableValues = new HashSet<>();

        for (IrInstruction instruction : block.instructions()) {
            validateInstruction(method, instruction, availableValues, producedValueIds);
        }

        validateTerminator(method, block.terminator(), blockByLabel, availableValues);
    }

    private void validateInstruction(IrMethod method, IrInstruction instruction, Set<Integer> availableValues, Set<Integer> producedValueIds) {
        switch (instruction) {
            case IrInstruction.Const constant -> registerProducedValue(method, constant.result(), availableValues, producedValueIds);
            case IrInstruction.LoadLocal loadLocal -> {
                ensureValidSlot(method, loadLocal.slot(), "load_local");
                registerProducedValue(method, loadLocal.result(), availableValues, producedValueIds);
            }
            case IrInstruction.StoreLocal storeLocal -> {
                ensureValidSlot(method, storeLocal.slot(), "store_local");
                requireAvailable(method, storeLocal.value(), availableValues, "store_local value");
            }
            case IrInstruction.Binary binary -> {
                requireAvailable(method, binary.left(), availableValues, "binary left operand");
                requireAvailable(method, binary.right(), availableValues, "binary right operand");
                ensureSameType(method, binary.left(), binary.right(), "binary operands");
                ensureNotVoid(method, binary.result(), "binary result");
                registerProducedValue(method, binary.result(), availableValues, producedValueIds);
            }
            case IrInstruction.Compare compare -> {
                requireAvailable(method, compare.left(), availableValues, "compare left operand");
                requireAvailable(method, compare.right(), availableValues, "compare right operand");
                if (isReferenceLike(compare.left().type()) || isReferenceLike(compare.right().type())) {
                    if ((compare.opcode() != xyz.melodysky.ir.model.IrCompareOpcode.EQ
                            && compare.opcode() != xyz.melodysky.ir.model.IrCompareOpcode.NE)
                            || !isValueCompatible(compare.left().type(), compare.right().type())) {
                        throw error(method, "compare operands must be compatible reference-like values for "
                                + compare.opcode() + " but saw "
                                + compare.left().type().displayName() + " and "
                                + compare.right().type().displayName());
                    }
                } else {
                    ensureSameType(method, compare.left(), compare.right(), "compare operands");
                }
                if (compare.result().type() != IrType.BOOLEAN) {
                    throw error(method, "compare result must have boolean type in block validation");
                }
                registerProducedValue(method, compare.result(), availableValues, producedValueIds);
            }
            case IrInstruction.Convert convert -> {
                requireAvailable(method, convert.value(), availableValues, "convert source");
                boolean intLike = isIntLike(convert.result().type()) && isIntLike(convert.value().type());
                boolean numeric = isNumericPrimitive(convert.result().type()) && isNumericPrimitive(convert.value().type());
                boolean referenceLike = isReferenceLike(convert.result().type()) && isReferenceLike(convert.value().type());
                if (!intLike && !numeric && !referenceLike) {
                    throw error(method, "convert only supports numeric/reference-like types but saw "
                            + convert.value().type().displayName() + " -> " + convert.result().type().displayName());
                }
                registerProducedValue(method, convert.result(), availableValues, producedValueIds);
            }
            case IrInstruction.LoadField loadField -> {
                requireAvailable(method, loadField.owner(), availableValues, "load_field owner");
                ensureReferenceLike(method, loadField.owner(), "load_field owner");
                if (loadField.field().isStatic()) {
                    throw error(method, "load_field requires instance field reference");
                }
                registerProducedValue(method, loadField.result(), availableValues, producedValueIds);
            }
            case IrInstruction.LoadStaticField loadStaticField -> {
                if (!loadStaticField.field().isStatic()) {
                    throw error(method, "load_static_field requires static field reference");
                }
                registerProducedValue(method, loadStaticField.result(), availableValues, producedValueIds);
            }
            case IrInstruction.NewObject newObject -> {
                if (newObject.result().type().isPrimitive() || newObject.result().type() == IrType.VOID) {
                    throw error(method, "new_object must produce a reference-like result");
                }
                registerProducedValue(method, newObject.result(), availableValues, producedValueIds);
            }
            case IrInstruction.StoreField storeField -> {
                requireAvailable(method, storeField.owner(), availableValues, "store_field owner");
                ensureReferenceLike(method, storeField.owner(), "store_field owner");
                if (storeField.field().isStatic()) {
                    throw error(method, "store_field requires instance field reference");
                }
                requireAvailable(method, storeField.value(), availableValues, "store_field value");
            }
            case IrInstruction.StoreStaticField storeStaticField -> {
                if (!storeStaticField.field().isStatic()) {
                    throw error(method, "store_static_field requires static field reference");
                }
                requireAvailable(method, storeStaticField.value(), availableValues, "store_static_field value");
            }
            case IrInstruction.Invoke invoke -> {
                for (IrValue argument : invoke.arguments()) {
                    requireAvailable(method, argument, availableValues, "invoke argument");
                }
                int expectedCount = invoke.method().callKind() == xyz.melodysky.ir.model.IrMethodRef.CallKind.STATIC
                        || invoke.method().callKind() == xyz.melodysky.ir.model.IrMethodRef.CallKind.HELPER
                        ? invoke.method().parameterTypes().size()
                        : invoke.method().parameterTypes().size() + 1;
                if (invoke.arguments().size() != expectedCount) {
                    throw error(method, "invoke argument count " + invoke.arguments().size()
                            + " does not match expected count " + expectedCount);
                }
                int argumentOffset = 0;
                if (invoke.method().callKind() != xyz.melodysky.ir.model.IrMethodRef.CallKind.STATIC
                        && invoke.method().callKind() != xyz.melodysky.ir.model.IrMethodRef.CallKind.HELPER) {
                    ensureReferenceLike(method, invoke.arguments().get(0), "invoke receiver");
                    argumentOffset = 1;
                }
                for (int index = 0; index < invoke.method().parameterTypes().size(); index++) {
                    IrValue argument = invoke.arguments().get(index + argumentOffset);
                    IrType expectedType = invoke.method().parameterTypes().get(index);
                    if (!isValueCompatible(argument.type(), expectedType)) {
                        throw error(method, "invoke argument " + index + " type " + argument.type().displayName()
                                + " does not match expected " + expectedType.displayName());
                    }
                }
                if (invoke.method().returnType() == IrType.VOID) {
                    if (invoke.result().type() != IrType.VOID) {
                        throw error(method, "void invoke must use a void placeholder result");
                    }
                } else {
                    if (!invoke.result().type().equals(invoke.method().returnType())) {
                        throw error(method, "invoke result type " + invoke.result().type().displayName()
                                + " does not match declared return type " + invoke.method().returnType().displayName());
                    }
                    ensureNotVoid(method, invoke.result(), "invoke result");
                    registerProducedValue(method, invoke.result(), availableValues, producedValueIds);
                }
            }
            case IrInstruction.CallHelper helper -> {
                for (IrValue argument : helper.arguments()) {
                    requireAvailable(method, argument, availableValues, "helper argument");
                }
                ensureNotVoid(method, helper.result(), "helper result");
                registerProducedValue(method, helper.result(), availableValues, producedValueIds);
            }
            case IrInstruction.CallHelperVoid helper -> {
                for (IrValue argument : helper.arguments()) {
                    requireAvailable(method, argument, availableValues, "helper_void argument");
                }
            }
        }
    }

    private void validateTerminator(IrMethod method, IrTerminator terminator, Map<String, IrBlock> blockByLabel, Set<Integer> availableValues) {
        switch (terminator) {
            case IrTerminator.Goto goTo -> ensureTargetExists(method, goTo.targetBlock(), blockByLabel);
            case IrTerminator.Branch branch -> {
                requireAvailable(method, branch.condition(), availableValues, "branch condition");
                if (branch.condition().type() != IrType.BOOLEAN) {
                    throw error(method, "branch condition must be boolean but was " + branch.condition().type().displayName());
                }
                ensureTargetExists(method, branch.trueTarget(), blockByLabel);
                ensureTargetExists(method, branch.falseTarget(), blockByLabel);
            }
            case IrTerminator.Switch switchTerminator -> {
                requireAvailable(method, switchTerminator.selector(), availableValues, "switch selector");
                if (switchTerminator.selector().type() != IrType.INT) {
                    throw error(method, "switch selector must be int but was " + switchTerminator.selector().type().displayName());
                }
                ensureTargetExists(method, switchTerminator.defaultTarget(), blockByLabel);
                for (String target : switchTerminator.targetByKey().values()) {
                    ensureTargetExists(method, target, blockByLabel);
                }
            }
            case IrTerminator.Return returnTerminator -> {
                requireAvailable(method, returnTerminator.value(), availableValues, "return value");
                if (method.returnType() == IrType.VOID) {
                    throw error(method, "return terminator cannot be used in a void method");
                }
                if (!isValueCompatible(returnTerminator.value().type(), method.returnType())) {
                    throw error(method, "return value type " + returnTerminator.value().type().displayName()
                            + " does not match method return type " + method.returnType().displayName());
                }
            }
            case IrTerminator.ReturnVoid ignored -> {
                if (method.returnType() != IrType.VOID) {
                    throw error(method, "return_void terminator requires void method but return type is " + method.returnType().displayName());
                }
            }
            case IrTerminator.Throw throwTerminator -> {
                requireAvailable(method, throwTerminator.exceptionValue(), availableValues, "throw value");
                if (throwTerminator.exceptionValue().type().isPrimitive() || throwTerminator.exceptionValue().type() == IrType.VOID) {
                    throw error(method, "throw value must be a reference-like type but was " + throwTerminator.exceptionValue().type().displayName());
                }
            }
            case IrTerminator.Unreachable ignored -> {
            }
        }
    }

    private void registerProducedValue(IrMethod method, IrValue value, Set<Integer> availableValues, Set<Integer> producedValueIds) {
        ensureNotVoid(method, value, "produced value");
        if (!producedValueIds.add(value.id())) {
            throw error(method, "duplicate value id %" + value.id());
        }
        availableValues.add(value.id());
    }

    private void requireAvailable(IrMethod method, IrValue value, Set<Integer> availableValues, String usage) {
        if (!availableValues.contains(value.id())) {
            throw error(method, usage + " uses undefined value " + value.symbol());
        }
    }

    private void ensureTargetExists(IrMethod method, String targetBlock, Map<String, IrBlock> blockByLabel) {
        if (!blockByLabel.containsKey(targetBlock)) {
            throw error(method, "terminator target does not exist: " + targetBlock);
        }
    }

    private void ensureValidSlot(IrMethod method, int slot, String operation) {
        if (slot < 0 || slot >= method.maxLocals()) {
            throw error(method, operation + " references local slot " + slot + " outside maxLocals=" + method.maxLocals());
        }
    }

    private void ensureSameType(IrMethod method, IrValue left, IrValue right, String usage) {
        if (!left.type().equals(right.type())) {
            throw error(method, usage + " must have matching types but saw "
                    + left.type().displayName() + " and " + right.type().displayName());
        }
    }

    private void ensureNotVoid(IrMethod method, IrValue value, String usage) {
        if (value.type() == IrType.VOID) {
            throw error(method, usage + " cannot have void type");
        }
    }

    private boolean isValueCompatible(IrType actualType, IrType expectedType) {
        if (actualType.equals(expectedType)) {
            return true;
        }
        if (isIntLike(actualType) && isIntLike(expectedType)) {
            return true;
        }
        return isReferenceLike(actualType) && isReferenceLike(expectedType);
    }

    private boolean isIntLike(IrType type) {
        return type == IrType.BOOLEAN
                || type == IrType.BYTE
                || type == IrType.SHORT
                || type == IrType.CHAR
                || type == IrType.INT;
    }

    private boolean isNumericPrimitive(IrType type) {
        return type.isPrimitive() && type != IrType.BOOLEAN;
    }

    private boolean isReferenceLike(IrType type) {
        return !type.isPrimitive() && type != IrType.VOID;
    }

    private void ensureReferenceLike(IrMethod method, IrValue value, String usage) {
        if (!isReferenceLike(value.type())) {
            throw error(method, usage + " must be reference-like but was " + value.type().displayName());
        }
    }

    private IrValidationException error(IrMethod method, String message) {
        return new IrValidationException("Invalid IR for method " + method.name() + ": " + message);
    }
}
