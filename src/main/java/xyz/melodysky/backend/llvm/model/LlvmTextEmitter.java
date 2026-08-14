package xyz.melodysky.backend.llvm.model;

import java.util.stream.Collectors;

public final class LlvmTextEmitter {
    public String emit(LlvmModule module) {
        return emit(LlvmModuleEmissionPlan.create(module), LlvmUnwindEmissionMode.RETAIN);
    }

    public String emit(
            LlvmModuleEmissionPlan plan,
            LlvmUnwindEmissionMode unwindMode) {
        java.util.Objects.requireNonNull(plan, "plan");
        java.util.Objects.requireNonNull(unwindMode, "unwindMode");
        if (unwindMode == LlvmUnwindEmissionMode.OMIT_PROVEN
                && !plan.proof().omissionSafe()) {
            throw new IllegalStateException(
                    "cannot omit LLVM unwind information: "
                            + plan.proof().reasonCode());
        }
        LlvmModule module = plan.module();
        StringBuilder output = new StringBuilder();
        output.append("; ModuleID = '").append(module.identifier()).append("'\n");
        for (LlvmDeclaration declaration : module.declarations()) {
            output.append('\n')
                    .append("declare ")
                    .append(declaration.returnType())
                    .append(" @")
                    .append(declaration.name())
                    .append('(')
                    .append(String.join(", ", declaration.parameterTypes()))
                    .append(')');
            if (declaration.comment() != null) {
                output.append(" ; ").append(declaration.comment());
            }
            output.append('\n');
        }
        for (LlvmGlobal global : module.globals()) {
            output.append('\n')
                    .append('@')
                    .append(global.name())
                    .append(" = ")
                    .append(global.definition())
                    .append('\n');
        }
        for (LlvmFunction function : module.functions()) {
            output.append('\n').append("define ")
                    .append(function.linkage().text()).append(' ')
                    .append(function.visibility().text()).append(' ')
                    .append(function.returnType().text()).append(" @")
                    .append(function.name()).append('(')
                    .append(function.parameters().stream()
                            .map(parameter -> parameter.type().text() + " " + parameter.name())
                            .collect(Collectors.joining(", ")))
                    .append(')');
            if (!function.attributes().isEmpty()) {
                output.append(' ')
                        .append(function.attributes().stream()
                                .map(LlvmFunctionAttribute::text)
                                .collect(Collectors.joining(" ")));
            }
            if (unwindMode == LlvmUnwindEmissionMode.OMIT_PROVEN) {
                output.append(" nounwind");
            }
            output.append(" {\n");
            for (LlvmBasicBlock block : function.blocks()) {
                output.append(block.name()).append(":\n");
                for (LlvmInstruction instruction : block.instructions()) {
                    output.append("  ");
                    if (instruction.directCall().isPresent()) {
                        LlvmDirectCallRef call =
                                instruction.directCall().orElseThrow();
                        instruction.result().ifPresent(result -> output.append(result).append(" = "));
                        output.append("call ")
                                .append(call.returnType().text())
                                .append(" @")
                                .append(call.target())
                                .append('(')
                                .append(call.arguments().stream()
                                        .map(argument -> argument.type().text()
                                                + " "
                                                + argument.value())
                                        .collect(Collectors.joining(", ")))
                                .append(')');
                    } else if (instruction.rawText().isPresent()) {
                        instruction.result().ifPresent(result -> output.append(result).append(" = "));
                        output.append(instruction.rawText().orElseThrow());
                    } else {
                        instruction.result().ifPresent(result -> output.append(result).append(" = "));
                        output.append(instruction.opcode()).append(' ')
                                .append(instruction.type().text());
                        if (!instruction.operands().isEmpty()) {
                            output.append(' ').append(String.join(", ", instruction.operands()));
                        }
                    }
                    output.append('\n');
                }
                if (block.terminator().kind() == LlvmTerminatorKind.RETURN) {
                    output.append("  ret ").append(block.terminator().returnType().text());
                    block.terminator().returnValue().ifPresent(value -> output.append(' ').append(value));
                } else if (block.terminator().kind() == LlvmTerminatorKind.THROW) {
                    output.append("  call void @j2ll_rt_throw(ptr %j2ll_env, ptr ")
                            .append(block.terminator().returnValue().orElseThrow())
                            .append(")\n")
                            .append("  ")
                            .append(pendingExceptionReturn(function.returnType()));
                } else if (block.terminator().kind() == LlvmTerminatorKind.GOTO) {
                    output.append("  br label %").append(block.terminator().target().orElseThrow());
                } else if (block.terminator().kind() == LlvmTerminatorKind.BRANCH) {
                    output.append("  br i1 ")
                            .append(block.terminator().condition().orElseThrow())
                            .append(", label %")
                            .append(block.terminator().trueTarget().orElseThrow())
                            .append(", label %")
                            .append(block.terminator().falseTarget().orElseThrow());
                } else {
                    output.append("  switch i32 ")
                            .append(block.terminator().switchValue().orElseThrow())
                            .append(", label %")
                            .append(block.terminator().defaultTarget().orElseThrow())
                            .append(" [\n");
                    for (LlvmSwitchCase switchCase : block.terminator().switchCases()) {
                        output.append("    i32 ")
                                .append(switchCase.key())
                                .append(", label %")
                                .append(switchCase.target())
                                .append('\n');
                    }
                    output.append("  ]");
                }
                output.append('\n');
            }
            output.append("}\n");
        }
        return output.toString();
    }

    private String pendingExceptionReturn(LlvmType returnType) {
        if (returnType == LlvmType.VOID) {
            return "ret void";
        }
        return "ret " + returnType.text() + " " + defaultValue(returnType);
    }

    private String defaultValue(LlvmType returnType) {
        return switch (returnType) {
            case I1, I32, I64 -> "0";
            case F32 -> "0.0";
            case F64 -> "0.0";
            case PTR -> "null";
            case VOID -> throw new IllegalArgumentException("void has no default value");
        };
    }
}
