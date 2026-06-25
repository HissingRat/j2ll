package xyz.melodysky.frontend.bytecode;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.FieldInsnNode;
import xyz.melodysky.ir.model.IrClassRef;
import xyz.melodysky.ir.model.IrFieldRef;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;

final class FieldLowerer {
    private FieldLowerer() {
    }

    static void lower(MethodIrBuilder support, BytecodeLoweringContext context, FieldInsnNode fieldInsn) {
        IrType fieldType = support.lowerSupportedValueType(context.methodNode(), Type.getType(fieldInsn.desc), "field");
        IrFieldRef fieldRef = new IrFieldRef(
                new IrClassRef(fieldInsn.owner),
                fieldInsn.name,
                fieldType,
                fieldInsn.getOpcode() == Opcodes.GETSTATIC || fieldInsn.getOpcode() == Opcodes.PUTSTATIC
        );

        int nextValueId = context.nextValueId();
        switch (fieldInsn.getOpcode()) {
            case Opcodes.GETSTATIC -> {
                IrValue result = new IrValue(nextValueId++, fieldType, "field");
                context.currentInstructions().add(new IrInstruction.LoadStaticField(result, fieldRef));
                context.stack().push(result);
            }
            case Opcodes.PUTSTATIC -> {
                CoercedValue coercedValue = support.coerceForExpectedType(
                        context.currentInstructions(),
                        support.popValueOfExpectedType(context.stack(), context.methodNode(), fieldType, "putstatic value"),
                        fieldType,
                        nextValueId
                );
                context.currentInstructions().add(new IrInstruction.StoreStaticField(fieldRef, coercedValue.value()));
                nextValueId = coercedValue.nextValueId();
            }
            case Opcodes.GETFIELD -> {
                IrValue owner = support.popReferenceLike(context.stack(), context.methodNode());
                IrValue result = new IrValue(nextValueId++, fieldType, "field");
                context.currentInstructions().add(new IrInstruction.LoadField(result, fieldRef, owner));
                context.stack().push(result);
            }
            case Opcodes.PUTFIELD -> {
                CoercedValue coercedValue = support.coerceForExpectedType(
                        context.currentInstructions(),
                        support.popValueOfExpectedType(context.stack(), context.methodNode(), fieldType, "putfield value"),
                        fieldType,
                        nextValueId
                );
                IrValue value = coercedValue.value();
                nextValueId = coercedValue.nextValueId();
                IrValue owner = support.popReferenceLike(context.stack(), context.methodNode());
                context.currentInstructions().add(new IrInstruction.StoreField(fieldRef, owner, value));
            }
            default -> throw support.unsupported(context.methodNode(), fieldInsn,
                    "only GETSTATIC/PUTSTATIC/GETFIELD/PUTFIELD are supported in the current slice");
        }
        context.setNextValueId(nextValueId);
    }
}
