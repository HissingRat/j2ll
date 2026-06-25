package xyz.melodysky.frontend.bytecode;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;

import java.util.ArrayList;
import java.util.List;

final class InvokeDynamicLowerer {
    private static final int LAMBDA_METAFACTORY_FLAG_MARKERS = 1 << 1;
    private static final int LAMBDA_METAFACTORY_FLAG_BRIDGES = 1 << 2;

    private InvokeDynamicLowerer() {
    }

    static void lower(MethodIrBuilder support, BytecodeLoweringContext context, InvokeDynamicInsnNode indyInsn) {
        Handle bootstrap = indyInsn.bsm;
        if ("java/lang/invoke/StringConcatFactory".equals(bootstrap.getOwner())
                && "makeConcatWithConstants".equals(bootstrap.getName())) {
            lowerStringConcat(support, context, indyInsn);
            return;
        }
        if ("java/lang/invoke/LambdaMetafactory".equals(bootstrap.getOwner())
                && ("metafactory".equals(bootstrap.getName()) || "altMetafactory".equals(bootstrap.getName()))) {
            lowerLambdaMetafactory(support, context, indyInsn);
            return;
        }
        if ("java/lang/runtime/SwitchBootstraps".equals(bootstrap.getOwner())
                && ("typeSwitch".equals(bootstrap.getName()) || "enumSwitch".equals(bootstrap.getName()))) {
            lowerSwitch(support, context, indyInsn);
            return;
        }
        if ("java/lang/runtime/ObjectMethods".equals(bootstrap.getOwner())
                && "bootstrap".equals(bootstrap.getName())) {
            lowerRecordObjectMethod(support, context, indyInsn);
            return;
        }
        throw support.unsupported(context.methodNode(), indyInsn, "invokedynamic lowering is not implemented yet");
    }

    private static void lowerStringConcat(MethodIrBuilder support, BytecodeLoweringContext context, InvokeDynamicInsnNode indyInsn) {
        Type asmMethodType = Type.getMethodType(indyInsn.desc);
        Type[] asmArgumentTypes = asmMethodType.getArgumentTypes();
        ArrayList<IrType> parameterTypes = new ArrayList<>(asmArgumentTypes.length);
        ArrayList<IrValue> arguments = new ArrayList<>(asmArgumentTypes.length);
        int nextValueId = context.nextValueId();

        for (int index = asmArgumentTypes.length - 1; index >= 0; index--) {
            IrType parameterType = support.lowerSupportedValueType(context.methodNode(), asmArgumentTypes[index], "concat parameter");
            parameterTypes.add(0, parameterType);
            CoercedValue coercedValue = support.coerceForExpectedType(
                    context.currentInstructions(),
                    support.popValueOfExpectedType(context.stack(), context.methodNode(), parameterType, "concat argument"),
                    parameterType,
                    nextValueId
            );
            nextValueId = coercedValue.nextValueId();
            arguments.add(0, coercedValue.value());
        }

        IrType returnType = support.lowerType(asmMethodType.getReturnType());
        if (!returnType.equals(IrType.reference("java/lang/String"))) {
            throw support.unsupported(context.methodNode(), indyInsn, "only String concat invokedynamic results are supported");
        }
        if (indyInsn.bsmArgs.length == 0 || !(indyInsn.bsmArgs[0] instanceof String recipe)) {
            throw support.unsupported(context.methodNode(), indyInsn, "string concat bootstrap is missing a recipe");
        }
        recipe = expandConcatConstants(support, context, indyInsn, recipe);
        if (support.countRecipeArguments(recipe) != parameterTypes.size()) {
            throw support.unsupported(context.methodNode(), indyInsn, "string concat recipe argument count does not match callsite");
        }

        IrValue result = new IrValue(nextValueId++, returnType, "concat");
        context.currentInstructions().add(new IrInstruction.CallHelper(
                result,
                support.concatHelperName(recipe, parameterTypes),
                List.copyOf(arguments)
        ));
        context.stack().push(result);
        context.setNextValueId(nextValueId);
    }

    private static String expandConcatConstants(MethodIrBuilder support, BytecodeLoweringContext context,
                                                InvokeDynamicInsnNode indyInsn, String recipe) {
        if (recipe.indexOf('\u0002') < 0) {
            return recipe;
        }
        StringBuilder expanded = new StringBuilder();
        int constantIndex = 1;
        for (int index = 0; index < recipe.length(); index++) {
            char current = recipe.charAt(index);
            if (current != '\u0002') {
                expanded.append(current);
                continue;
            }
            if (constantIndex >= indyInsn.bsmArgs.length) {
                throw support.unsupported(context.methodNode(), indyInsn, "string concat recipe has more constant placeholders than bootstrap constants");
            }
            expanded.append(stringConcatConstantText(support, context, indyInsn, indyInsn.bsmArgs[constantIndex++]));
        }
        if (constantIndex < indyInsn.bsmArgs.length) {
            throw support.unsupported(context.methodNode(), indyInsn, "string concat bootstrap has unused constants");
        }
        return expanded.toString();
    }

    private static String stringConcatConstantText(MethodIrBuilder support, BytecodeLoweringContext context,
                                                   InvokeDynamicInsnNode indyInsn, Object constant) {
        if (constant == null || constant instanceof String || constant instanceof Integer || constant instanceof Long
                || constant instanceof Float || constant instanceof Double || constant instanceof Character
                || constant instanceof Short || constant instanceof Byte || constant instanceof Boolean) {
            String value = String.valueOf(constant);
            if (value.indexOf('\u0001') >= 0) {
                throw support.unsupported(context.methodNode(), indyInsn, "string concat constants cannot contain argument placeholders");
            }
            return value;
        }
        throw support.unsupported(context.methodNode(), indyInsn, "unsupported string concat constant bootstrap argument: "
                + constant.getClass().getName());
    }

    private static void lowerLambdaMetafactory(MethodIrBuilder support, BytecodeLoweringContext context, InvokeDynamicInsnNode indyInsn) {
        Type indyMethodType = Type.getMethodType(indyInsn.desc);
        if (!(indyInsn.bsmArgs.length >= 3
                && indyInsn.bsmArgs[0] instanceof Type samMethodType
                && indyInsn.bsmArgs[1] instanceof Handle implMethod
                && indyInsn.bsmArgs[2] instanceof Type instantiatedMethodType)) {
            throw support.unsupported(context.methodNode(), indyInsn, "lambda metafactory is missing implementation handle");
        }

        IrType interfaceType = support.lowerType(indyMethodType.getReturnType());
        if (interfaceType.isPrimitive() || interfaceType == IrType.VOID) {
            throw support.unsupported(context.methodNode(), indyInsn, "lambda metafactory return type must be reference-like");
        }

        ArrayList<IrType> captureTypes = new ArrayList<>();
        ArrayList<IrValue> captureArguments = new ArrayList<>();
        Type[] captureAsmTypes = indyMethodType.getArgumentTypes();
        int nextValueId = context.nextValueId();
        for (int index = captureAsmTypes.length - 1; index >= 0; index--) {
            IrType captureType = support.lowerSupportedValueType(context.methodNode(), captureAsmTypes[index], "lambda capture");
            captureTypes.add(0, captureType);
            CoercedValue coercedValue = support.coerceForExpectedType(
                    context.currentInstructions(),
                    support.popValueOfExpectedType(context.stack(), context.methodNode(), captureType, "lambda capture"),
                    captureType,
                    nextValueId
            );
            nextValueId = coercedValue.nextValueId();
            captureArguments.add(0, coercedValue.value());
        }

        LambdaBootstrapMetadata bootstrapMetadata = lambdaBootstrapMetadata(support, context, indyInsn);
        IrValue result = new IrValue(nextValueId++, interfaceType, "lambda");
        context.currentInstructions().add(new IrInstruction.CallHelper(
                result,
                support.lambdaHelperName(context.ownerInternalName(), interfaceType, indyInsn.name, samMethodType, implMethod, instantiatedMethodType, captureTypes, bootstrapMetadata),
                List.copyOf(captureArguments)
        ));
        context.stack().push(result);
        context.setNextValueId(nextValueId);
    }

    private static LambdaBootstrapMetadata lambdaBootstrapMetadata(MethodIrBuilder support, BytecodeLoweringContext context,
                                                                   InvokeDynamicInsnNode indyInsn) {
        String bootstrapMethodName = indyInsn.bsm.getName();
        if ("metafactory".equals(bootstrapMethodName)) {
            return new LambdaBootstrapMetadata("metafactory", 0, List.of(), List.of());
        }
        if (!"altMetafactory".equals(bootstrapMethodName)) {
            throw support.unsupported(context.methodNode(), indyInsn, "unsupported lambda metafactory bootstrap " + bootstrapMethodName);
        }
        if (!(indyInsn.bsmArgs.length >= 4 && indyInsn.bsmArgs[3] instanceof Integer flags)) {
            throw support.unsupported(context.methodNode(), indyInsn, "altMetafactory is missing bootstrap flags");
        }
        int cursor = 4;
        ArrayList<IrType> markerInterfaces = new ArrayList<>();
        if ((flags & LAMBDA_METAFACTORY_FLAG_MARKERS) != 0) {
            if (!(cursor < indyInsn.bsmArgs.length && indyInsn.bsmArgs[cursor] instanceof Integer markerCount)) {
                throw support.unsupported(context.methodNode(), indyInsn, "altMetafactory markers are missing interface count");
            }
            cursor++;
            for (int index = 0; index < markerCount; index++) {
                if (!(cursor < indyInsn.bsmArgs.length && indyInsn.bsmArgs[cursor] instanceof Type markerType)) {
                    throw support.unsupported(context.methodNode(), indyInsn, "altMetafactory marker interface is missing type descriptor");
                }
                IrType markerInterface = support.lowerType(markerType);
                if (markerInterface.isPrimitive() || markerInterface == IrType.VOID || markerInterface.kind() == IrType.Kind.ARRAY) {
                    throw support.unsupported(context.methodNode(), indyInsn, "altMetafactory marker interfaces must be object types");
                }
                markerInterfaces.add(markerInterface);
                cursor++;
            }
        }
        ArrayList<Type> bridgeMethodTypes = new ArrayList<>();
        if ((flags & LAMBDA_METAFACTORY_FLAG_BRIDGES) != 0) {
            if (!(cursor < indyInsn.bsmArgs.length && indyInsn.bsmArgs[cursor] instanceof Integer bridgeCount)) {
                throw support.unsupported(context.methodNode(), indyInsn, "altMetafactory bridges are missing method type count");
            }
            cursor++;
            for (int index = 0; index < bridgeCount; index++) {
                if (!(cursor < indyInsn.bsmArgs.length && indyInsn.bsmArgs[cursor] instanceof Type bridgeType)) {
                    throw support.unsupported(context.methodNode(), indyInsn, "altMetafactory bridge descriptor is missing method type");
                }
                bridgeMethodTypes.add(bridgeType);
                cursor++;
            }
        }
        if (cursor != indyInsn.bsmArgs.length) {
            throw support.unsupported(context.methodNode(), indyInsn, "altMetafactory bootstrap has unexpected trailing arguments");
        }
        return new LambdaBootstrapMetadata(
                "altMetafactory",
                flags,
                List.copyOf(markerInterfaces),
                List.copyOf(bridgeMethodTypes)
        );
    }

    private static void lowerSwitch(MethodIrBuilder support, BytecodeLoweringContext context, InvokeDynamicInsnNode indyInsn) {
        Type indyMethodType = Type.getMethodType(indyInsn.desc);
        Type[] argumentTypes = indyMethodType.getArgumentTypes();
        if (argumentTypes.length != 2 || argumentTypes[1].getSort() != Type.INT) {
            throw support.unsupported(context.methodNode(), indyInsn, indyInsn.bsm.getName()
                    + " currently expects (reference, int) arguments");
        }

        int nextValueId = context.nextValueId();
        CoercedValue stateValue = support.popPromotedInt(context.currentInstructions(), context.stack(), context.methodNode(), nextValueId);
        IrValue state = stateValue.value();
        nextValueId = stateValue.nextValueId();
        String switchKind = indyInsn.bsm.getName();
        IrType subjectType = support.lowerSupportedValueType(context.methodNode(), argumentTypes[0], switchKind + " subject");
        IrValue subject = support.popValueOfExpectedType(context.stack(), context.methodNode(), subjectType, switchKind + " subject");

        IrValue result = new IrValue(nextValueId++, IrType.INT, switchKind);
        context.currentInstructions().add(new IrInstruction.CallHelper(
                result,
                support.switchHelperName(context.methodNode(), indyInsn, argumentTypes[0]),
                List.of(subject, state)
        ));
        context.stack().push(result);
        context.setNextValueId(nextValueId);
    }

    private static void lowerRecordObjectMethod(MethodIrBuilder support, BytecodeLoweringContext context, InvokeDynamicInsnNode indyInsn) {
        if (!(indyInsn.bsmArgs.length >= 2
                && indyInsn.bsmArgs[0] instanceof Type recordType
                && indyInsn.bsmArgs[1] instanceof String)) {
            throw support.unsupported(context.methodNode(), indyInsn, "record ObjectMethods bootstrap metadata is malformed");
        }

        Type indyMethodType = Type.getMethodType(indyInsn.desc);
        Type[] asmArgumentTypes = indyMethodType.getArgumentTypes();
        ArrayList<IrValue> arguments = new ArrayList<>(asmArgumentTypes.length);
        for (int index = asmArgumentTypes.length - 1; index >= 0; index--) {
            IrType parameterType = support.lowerSupportedValueType(context.methodNode(), asmArgumentTypes[index], "record helper argument");
            arguments.add(0, support.popValueOfExpectedType(context.stack(), context.methodNode(), parameterType, "record helper argument"));
        }

        int nextValueId = context.nextValueId();
        IrType returnType = support.lowerType(indyMethodType.getReturnType());
        IrValue result = returnType == IrType.VOID
                ? null
                : new IrValue(nextValueId++, returnType, "record");
        context.currentInstructions().add(new IrInstruction.CallHelper(
                result,
                support.recordObjectMethodHelperName(recordType, indyInsn.name, indyInsn.bsmArgs),
                List.copyOf(arguments)
        ));
        if (result != null) {
            context.stack().push(result);
        }
        context.setNextValueId(nextValueId);
    }
}
