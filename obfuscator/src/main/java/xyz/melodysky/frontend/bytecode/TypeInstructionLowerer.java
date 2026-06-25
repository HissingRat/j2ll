package xyz.melodysky.frontend.bytecode;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.TypeInsnNode;
import xyz.melodysky.ir.model.IrClassRef;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;

import java.util.List;

final class TypeInstructionLowerer {
    private TypeInstructionLowerer() {
    }

    static void lower(MethodIrBuilder support, BytecodeLoweringContext context, TypeInsnNode typeInsn) {
        int nextValueId = context.nextValueId();
        if (typeInsn.getOpcode() == Opcodes.NEW) {
            IrType resultType = IrType.reference(typeInsn.desc);
            IrValue result = new IrValue(nextValueId++, resultType, "obj");
            context.currentInstructions().add(new IrInstruction.NewObject(result, new IrClassRef(typeInsn.desc)));
            context.stack().push(result);
            context.setNextValueId(nextValueId);
            return;
        }
        if (typeInsn.getOpcode() == Opcodes.ANEWARRAY) {
            CoercedValue sizeValue = support.popPromotedInt(context.currentInstructions(), context.stack(), context.methodNode(), nextValueId);
            IrValue size = sizeValue.value();
            nextValueId = sizeValue.nextValueId();
            IrType elementType = typeInsn.desc.startsWith("[")
                    ? support.lowerType(Type.getType(typeInsn.desc))
                    : IrType.reference(typeInsn.desc);
            IrType arrayType = IrType.array(elementType);
            IrValue result = new IrValue(nextValueId++, arrayType, "arr");
            context.currentInstructions().add(new IrInstruction.CallHelper(
                    result,
                    support.arrayCreationHelperName(arrayType),
                    List.of(size)
            ));
            context.stack().push(result);
            context.setNextValueId(nextValueId);
            return;
        }
        if (typeInsn.getOpcode() == Opcodes.CHECKCAST) {
            IrValue value = support.popReferenceLike(context.stack(), context.methodNode());
            IrType targetType = typeInsn.desc.startsWith("[")
                    ? support.lowerType(Type.getType(typeInsn.desc))
                    : IrType.reference(typeInsn.desc);
            IrValue casted = new IrValue(nextValueId++, targetType, "cast");
            context.currentInstructions().add(new IrInstruction.Convert(casted, value));
            context.stack().push(casted);
            context.setNextValueId(nextValueId);
            return;
        }
        if (typeInsn.getOpcode() == Opcodes.INSTANCEOF) {
            IrValue value = support.popReferenceLike(context.stack(), context.methodNode());
            IrType targetType = typeInsn.desc.startsWith("[")
                    ? support.lowerType(Type.getType(typeInsn.desc))
                    : IrType.reference(typeInsn.desc);
            IrValue result = new IrValue(nextValueId++, IrType.BOOLEAN, "instanceof");
            context.currentInstructions().add(new IrInstruction.CallHelper(
                    result,
                    support.instanceOfHelperName(targetType),
                    List.of(value)
            ));
            context.stack().push(result);
            context.setNextValueId(nextValueId);
            return;
        }
        throw support.unsupported(context.methodNode(), typeInsn, "only NEW/ANEWARRAY/CHECKCAST/INSTANCEOF are supported in the current slice");
    }
}
