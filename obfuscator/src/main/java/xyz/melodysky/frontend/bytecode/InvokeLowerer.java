package xyz.melodysky.frontend.bytecode;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.MethodInsnNode;
import xyz.melodysky.ir.model.IrClassRef;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethodRef;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;

import java.util.ArrayList;

final class InvokeLowerer {
    private InvokeLowerer() {
    }

    static void lower(MethodIrBuilder support, BytecodeLoweringContext context, MethodInsnNode methodInsn) {
        Type asmMethodType = Type.getMethodType(methodInsn.desc);
        Type[] asmArgumentTypes = asmMethodType.getArgumentTypes();
        ArrayList<IrType> parameterTypes = new ArrayList<>(asmArgumentTypes.length);
        ArrayList<IrValue> arguments = new ArrayList<>(asmArgumentTypes.length);
        int nextValueId = context.nextValueId();

        for (int index = asmArgumentTypes.length - 1; index >= 0; index--) {
            IrType parameterType = support.lowerSupportedValueType(context.methodNode(), asmArgumentTypes[index], "invoke parameter");
            parameterTypes.add(0, parameterType);
            CoercedValue coercedValue = support.coerceForExpectedType(
                    context.currentInstructions(),
                    support.popValueOfExpectedType(context.stack(), context.methodNode(), parameterType, "invoke argument"),
                    parameterType,
                    nextValueId
            );
            nextValueId = coercedValue.nextValueId();
            arguments.add(0, coercedValue.value());
        }

        IrMethodRef.CallKind callKind = switch (methodInsn.getOpcode()) {
            case Opcodes.INVOKESTATIC -> IrMethodRef.CallKind.STATIC;
            case Opcodes.INVOKEVIRTUAL -> IrMethodRef.CallKind.VIRTUAL;
            case Opcodes.INVOKESPECIAL -> IrMethodRef.CallKind.SPECIAL;
            case Opcodes.INVOKEINTERFACE -> IrMethodRef.CallKind.INTERFACE;
            default -> throw support.unsupported(context.methodNode(), methodInsn, "invoke opcode is not supported in the current slice");
        };

        if (callKind != IrMethodRef.CallKind.STATIC) {
            arguments.add(0, support.popReferenceLike(context.stack(), context.methodNode()));
        }

        IrType returnType = support.lowerInvokeReturnType(context.methodNode(), asmMethodType.getReturnType(), callKind);
        IrMethodRef methodRef = new IrMethodRef(
                new IrClassRef(methodInsn.owner),
                methodInsn.name,
                returnType,
                parameterTypes,
                callKind
        );

        IrValue result = new IrValue(nextValueId, returnType, returnType == IrType.VOID ? "void" : "call");
        context.currentInstructions().add(new IrInstruction.Invoke(result, methodRef, arguments));
        if (returnType != IrType.VOID) {
            context.stack().push(result);
            nextValueId++;
        }
        context.setNextValueId(nextValueId);
    }
}
