package xyz.melodysky.ir.print;

import xyz.melodysky.ir.model.*;

import java.util.StringJoiner;

public final class IrPrinter {

    public String print(IrProgram program) {
        StringBuilder builder = new StringBuilder();
        for (IrClass irClass : program.classes()) {
            appendClass(builder, irClass);
        }
        return builder.toString();
    }

    public String print(IrMethod method) {
        StringBuilder builder = new StringBuilder();
        appendMethod(builder, method, "");
        return builder.toString();
    }

    private void appendClass(StringBuilder builder, IrClass irClass) {
        builder.append("class ").append(irClass.reference().internalName()).append(" {\n");
        for (IrMethod method : irClass.methods()) {
            appendMethod(builder, method, "  ");
        }
        builder.append("}\n");
    }

    private void appendMethod(StringBuilder builder, IrMethod method, String indent) {
        builder.append(indent)
                .append("method ")
                .append(method.name())
                .append(method.isStatic() ? " [static]" : " [instance]")
                .append("(")
                .append(renderTypeList(method.parameterTypes()))
                .append(") -> ")
                .append(method.returnType().displayName())
                .append(" locals=")
                .append(method.maxLocals())
                .append(" entry=")
                .append(method.entryBlock())
                .append(" {\n");

        for (IrBlock block : method.blocks()) {
            builder.append(indent).append("  block ").append(block.label()).append(":\n");
            for (IrInstruction instruction : block.instructions()) {
                builder.append(indent)
                        .append("    ")
                        .append(renderInstruction(instruction))
                        .append('\n');
            }
            builder.append(indent)
                    .append("    ")
                    .append(renderTerminator(block.terminator()))
                    .append('\n');
        }

        builder.append(indent).append("}\n");
    }

    private String renderInstruction(IrInstruction instruction) {
        return switch (instruction) {
            case IrInstruction.Const constant ->
                    constant.result().typedSymbol() + " = const " + renderConstant(constant.value());
            case IrInstruction.LoadLocal loadLocal ->
                    loadLocal.result().typedSymbol() + " = load_local " + loadLocal.slot();
            case IrInstruction.StoreLocal storeLocal ->
                    "store_local " + storeLocal.slot() + ", " + renderValue(storeLocal.value());
            case IrInstruction.Binary binary ->
                    binary.result().typedSymbol() + " = " + binary.opcode().mnemonic() + " "
                            + renderValue(binary.left()) + ", " + renderValue(binary.right());
            case IrInstruction.Compare compare ->
                    compare.result().typedSymbol() + " = cmp." + compare.opcode().mnemonic() + " "
                            + renderValue(compare.left()) + ", " + renderValue(compare.right());
            case IrInstruction.Convert convert ->
                    convert.result().typedSymbol() + " = convert " + renderValue(convert.value());
            case IrInstruction.LoadField loadField ->
                    loadField.result().typedSymbol() + " = load_field "
                            + loadField.field().owner().internalName() + "." + loadField.field().name()
                            + " from " + renderValue(loadField.owner());
            case IrInstruction.LoadStaticField loadStaticField ->
                    loadStaticField.result().typedSymbol() + " = load_static_field "
                            + loadStaticField.field().owner().internalName() + "." + loadStaticField.field().name();
            case IrInstruction.NewObject newObject ->
                    newObject.result().typedSymbol() + " = new_object " + newObject.classRef().internalName();
            case IrInstruction.StoreField storeField ->
                    "store_field " + storeField.field().owner().internalName() + "." + storeField.field().name()
                            + ", owner=" + renderValue(storeField.owner())
                            + ", value=" + renderValue(storeField.value());
            case IrInstruction.StoreStaticField storeStaticField ->
                    "store_static_field " + storeStaticField.field().owner().internalName() + "." + storeStaticField.field().name()
                            + ", value=" + renderValue(storeStaticField.value());
            case IrInstruction.Invoke invoke ->
                    renderInvoke(invoke);
            case IrInstruction.CallHelper helper ->
                    helper.result().typedSymbol() + " = call_helper " + helper.helperName()
                            + "(" + renderValueList(helper.arguments()) + ")";
            case IrInstruction.CallHelperVoid helper ->
                    "call_helper_void " + helper.helperName()
                            + "(" + renderValueList(helper.arguments()) + ")";
        };
    }

    private String renderInvoke(IrInstruction.Invoke invoke) {
        String call = "invoke " + invoke.method().callKind().name().toLowerCase()
                + " " + invoke.method().owner().internalName() + "." + invoke.method().name()
                + "(" + renderValueList(invoke.arguments()) + ")";
        if (invoke.result().type() == IrType.VOID) {
            return call;
        }
        return invoke.result().typedSymbol() + " = " + call;
    }

    private String renderTerminator(IrTerminator terminator) {
        return switch (terminator) {
            case IrTerminator.Goto goTo -> "goto " + goTo.targetBlock();
            case IrTerminator.Branch branch -> "branch " + renderValue(branch.condition())
                    + " ? " + branch.trueTarget() + " : " + branch.falseTarget();
            case IrTerminator.Switch switchTerminator ->
                    "switch " + renderValue(switchTerminator.selector()) + " " + switchTerminator.targetByKey()
                            + " default " + switchTerminator.defaultTarget();
            case IrTerminator.Return returnTerminator ->
                    "return " + renderValue(returnTerminator.value());
            case IrTerminator.ReturnVoid ignored -> "return_void";
            case IrTerminator.Throw throwTerminator ->
                    "throw " + renderValue(throwTerminator.exceptionValue());
            case IrTerminator.Unreachable ignored -> "unreachable";
        };
    }

    private String renderConstant(Object value) {
        if (value instanceof String stringValue) {
            return "\"" + stringValue.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return String.valueOf(value);
    }

    private String renderValue(IrValue value) {
        return value.symbol();
    }

    private String renderValueList(Iterable<IrValue> values) {
        StringJoiner joiner = new StringJoiner(", ");
        for (IrValue value : values) {
            joiner.add(renderValue(value));
        }
        return joiner.toString();
    }

    private String renderTypeList(Iterable<IrType> types) {
        StringJoiner joiner = new StringJoiner(", ");
        for (IrType type : types) {
            joiner.add(type.displayName());
        }
        return joiner.toString();
    }
}
