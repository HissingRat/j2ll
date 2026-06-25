package xyz.melodysky.frontend.bytecode;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;

import java.util.ArrayList;
import java.util.List;

final class ArrayLowerer {
    private ArrayLowerer() {
    }

    static void lowerNewPrimitiveArray(MethodIrBuilder support, BytecodeLoweringContext context, IntInsnNode intInsn) {
        int nextValueId = context.nextValueId();
        CoercedValue sizeValue = support.popPromotedInt(context.currentInstructions(), context.stack(), context.methodNode(), nextValueId);
        IrValue size = sizeValue.value();
        nextValueId = sizeValue.nextValueId();
        IrType elementType = switch (intInsn.operand) {
            case Opcodes.T_BOOLEAN -> IrType.BOOLEAN;
            case Opcodes.T_BYTE -> IrType.BYTE;
            case Opcodes.T_CHAR -> IrType.CHAR;
            case Opcodes.T_SHORT -> IrType.SHORT;
            case Opcodes.T_INT -> IrType.INT;
            case Opcodes.T_LONG -> IrType.LONG;
            case Opcodes.T_FLOAT -> IrType.FLOAT;
            case Opcodes.T_DOUBLE -> IrType.DOUBLE;
            default -> throw support.unsupported(context.methodNode(), intInsn, "unsupported primitive array element type");
        };
        IrType arrayType = IrType.array(elementType);
        IrValue result = new IrValue(nextValueId++, arrayType, "arr");
        context.currentInstructions().add(new IrInstruction.CallHelper(
                result,
                support.arrayCreationHelperName(arrayType),
                List.of(size)
        ));
        context.stack().push(result);
        context.setNextValueId(nextValueId);
    }

    static void lowerMultiNewArray(MethodIrBuilder support, BytecodeLoweringContext context, MultiANewArrayInsnNode multiArrayInsn) {
        int nextValueId = context.nextValueId();
        IrType arrayType = support.lowerType(Type.getType(multiArrayInsn.desc));
        ArrayList<IrValue> dimensions = new ArrayList<>(multiArrayInsn.dims);
        for (int index = 0; index < multiArrayInsn.dims; index++) {
            CoercedValue dimValue = support.popPromotedInt(context.currentInstructions(), context.stack(), context.methodNode(), nextValueId);
            dimensions.add(0, dimValue.value());
            nextValueId = dimValue.nextValueId();
        }
        IrValue result = new IrValue(nextValueId++, arrayType, "arr");
        context.currentInstructions().add(new IrInstruction.CallHelper(
                result,
                support.multiArrayCreationHelperName(arrayType),
                List.copyOf(dimensions)
        ));
        context.stack().push(result);
        context.setNextValueId(nextValueId);
    }

    static void lowerArrayLoad(MethodIrBuilder support, BytecodeLoweringContext context,
                               Frame<BasicValue> instructionFrame, IrType expectedElementType, IrType resultType) {
        int nextValueId = context.nextValueId();
        CoercedValue indexValue = support.popPromotedInt(context.currentInstructions(), context.stack(), context.methodNode(), nextValueId);
        IrValue index = indexValue.value();
        nextValueId = indexValue.nextValueId();
        IrValue array = support.popReferenceLike(context.stack(), context.methodNode());
        IrType arrayType = support.resolveArrayOperandType(context.methodNode(), array, instructionFrame, 1);
        IrType elementType = support.arrayElementType(arrayType);
        if (!support.matchesArrayOpcodeElementType(expectedElementType, elementType)) {
            throw new UnsupportedBytecodeException("Expected " + expectedElementType.displayName() + "[] for array load in "
                    + context.methodNode().name + context.methodNode().desc + " but found " + arrayType.displayName());
        }
        IrValue result = new IrValue(nextValueId++, resultType, "elem");
        context.currentInstructions().add(new IrInstruction.CallHelper(
                result,
                support.arrayLoadHelperName(arrayType),
                List.of(array, index)
        ));
        context.stack().push(result);
        context.setNextValueId(nextValueId);
    }

    static void lowerReferenceArrayLoad(MethodIrBuilder support, BytecodeLoweringContext context,
                                        Frame<BasicValue> instructionFrame) {
        int nextValueId = context.nextValueId();
        CoercedValue indexValue = support.popPromotedInt(context.currentInstructions(), context.stack(), context.methodNode(), nextValueId);
        IrValue index = indexValue.value();
        nextValueId = indexValue.nextValueId();
        IrValue array = support.popReferenceLike(context.stack(), context.methodNode());
        IrType arrayType = support.resolveArrayOperandType(context.methodNode(), array, instructionFrame, 1);
        IrType elementType = support.arrayElementType(arrayType);
        if (elementType.isPrimitive()) {
            throw new UnsupportedBytecodeException("AALOAD requires reference-like element type in "
                    + context.methodNode().name + context.methodNode().desc + " but found " + elementType.displayName());
        }
        IrValue result = new IrValue(nextValueId++, elementType, "elem");
        context.currentInstructions().add(new IrInstruction.CallHelper(
                result,
                support.arrayLoadHelperName(arrayType),
                List.of(array, index)
        ));
        context.stack().push(result);
        context.setNextValueId(nextValueId);
    }

    static void lowerArrayStore(MethodIrBuilder support, BytecodeLoweringContext context,
                                Frame<BasicValue> instructionFrame, IrType expectedElementType) {
        int nextValueId = context.nextValueId();
        IrValue value = expectedElementType == IrType.BOOLEAN
                || expectedElementType == IrType.BYTE
                || expectedElementType == IrType.SHORT
                || expectedElementType == IrType.CHAR
                ? support.popIntLike(context.stack(), context.methodNode())
                : support.popValueOfExpectedType(context.stack(), context.methodNode(), expectedElementType, "array store");
        if (value.type() != expectedElementType) {
            CoercedValue coercedValue = support.coerceForExpectedType(context.currentInstructions(), value, expectedElementType, nextValueId);
            value = coercedValue.value();
            nextValueId = coercedValue.nextValueId();
        }
        CoercedValue indexValue = support.popPromotedInt(context.currentInstructions(), context.stack(), context.methodNode(), nextValueId);
        IrValue index = indexValue.value();
        nextValueId = indexValue.nextValueId();
        IrValue array = support.popReferenceLike(context.stack(), context.methodNode());
        IrType arrayType = support.resolveArrayOperandType(context.methodNode(), array, instructionFrame, 2);
        IrType elementType = support.arrayElementType(arrayType);
        if (!support.matchesArrayOpcodeElementType(expectedElementType, elementType)) {
            throw new UnsupportedBytecodeException("Expected " + expectedElementType.displayName() + "[] for array store in "
                    + context.methodNode().name + context.methodNode().desc + " but found " + arrayType.displayName());
        }
        context.currentInstructions().add(new IrInstruction.CallHelperVoid(
                support.arrayStoreHelperName(arrayType),
                List.of(array, index, value)
        ));
        context.setNextValueId(nextValueId);
    }

    static void lowerReferenceArrayStore(MethodIrBuilder support, BytecodeLoweringContext context,
                                         Frame<BasicValue> instructionFrame) {
        int nextValueId = context.nextValueId();
        IrValue value = support.popReferenceLike(context.stack(), context.methodNode());
        CoercedValue indexValue = support.popPromotedInt(context.currentInstructions(), context.stack(), context.methodNode(), nextValueId);
        IrValue index = indexValue.value();
        nextValueId = indexValue.nextValueId();
        IrValue array = support.popReferenceLike(context.stack(), context.methodNode());
        IrType arrayType = support.resolveArrayOperandType(context.methodNode(), array, instructionFrame, 2);
        IrType elementType = support.arrayElementType(arrayType);
        if (elementType.isPrimitive()) {
            throw new UnsupportedBytecodeException("AASTORE requires reference-like element type in "
                    + context.methodNode().name + context.methodNode().desc + " but found " + elementType.displayName());
        }
        context.currentInstructions().add(new IrInstruction.CallHelperVoid(
                support.arrayStoreHelperName(arrayType),
                List.of(array, index, value)
        ));
        context.setNextValueId(nextValueId);
    }

    static void lowerArrayLength(MethodIrBuilder support, BytecodeLoweringContext context,
                                 Frame<BasicValue> instructionFrame) {
        IrValue array = support.popReferenceLike(context.stack(), context.methodNode());
        support.resolveArrayOperandType(context.methodNode(), array, instructionFrame, 0);
        IrValue result = new IrValue(context.nextValueId(), IrType.INT, "len");
        context.setNextValueId(context.nextValueId() + 1);
        context.currentInstructions().add(new IrInstruction.CallHelper(
                result,
                "ir_rt_array_length",
                List.of(array)
        ));
        context.stack().push(result);
    }
}
