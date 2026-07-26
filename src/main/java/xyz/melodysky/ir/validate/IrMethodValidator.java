package xyz.melodysky.ir.validate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticLocation;
import xyz.melodysky.diagnostic.DiagnosticStage;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrTerminatorKind;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;

public final class IrMethodValidator {
    public List<Diagnostic> validate(IrMethod method) {
        ArrayList<Diagnostic> diagnostics = new ArrayList<>();
        DiagnosticLocation location = DiagnosticLocation.methodLocation(method.owner(), method.name(), method.descriptor());
        if (method.blocks().isEmpty()) {
            diagnostics.add(Diagnostic.error(
                            DiagnosticStage.VALIDATION,
                            IrValidationDiagnostics.IR_METHOD_HAS_NO_BLOCKS,
                            "IR method has no blocks")
                    .at(location));
            return diagnostics;
        }

        Set<String> blockNames = new HashSet<>();
        Map<String, IrBlock> blocksByName = new HashMap<>();
        for (IrBlock block : method.blocks()) {
            blockNames.add(block.name());
            blocksByName.put(block.name(), block);
        }

        HashSet<IrValue> defined = new HashSet<>(method.parameters());
        for (IrBlock block : method.blocks()) {
            defined.addAll(block.parameters());
        }
        for (IrBlock block : method.blocks()) {
            for (var instruction : block.instructions()) {
                for (IrValue operand : instruction.operands()) {
                    if (!defined.contains(operand)) {
                        diagnostics.add(Diagnostic.error(
                                        DiagnosticStage.VALIDATION,
                                        IrValidationDiagnostics.IR_USE_BEFORE_DEF,
                                        "IR value used before definition: " + operand.name())
                                .at(location));
                    }
                }
                for (var site : instruction.exceptionSites()) {
                    validateExceptionEdges(site.handlers(), blocksByName, location, diagnostics);
                }
                validateMonitorInstruction(instruction.opcode(), instruction.operands(), location, diagnostics);
                validateClassInitInstruction(
                        instruction.opcode(),
                        instruction.result().orElse(null),
                        instruction.operands(),
                        location,
                        diagnostics);
                validateCallIndirection(instruction, location, diagnostics);
                instruction.result().ifPresent(defined::add);
            }
            validateHandlerShape(block, location, diagnostics);
            validateExceptionEdges(block.exceptionEdges(), blocksByName, location, diagnostics);
            if (block.terminator().kind() == IrTerminatorKind.RETURN) {
                if (method.returnType() == IrType.VOID && block.terminator().value().isPresent()) {
                    diagnostics.add(returnMismatch(location, "void method returns a value"));
                }
                if (method.returnType() != IrType.VOID && block.terminator().value().isEmpty()) {
                    diagnostics.add(returnMismatch(location, "non-void method returns void"));
                }
                block.terminator().value().ifPresent(value -> {
                    if (!defined.contains(value)) {
                        diagnostics.add(Diagnostic.error(
                                        DiagnosticStage.VALIDATION,
                                        IrValidationDiagnostics.IR_USE_BEFORE_DEF,
                                        "IR return uses value before definition: " + value.name())
                                .at(location));
                    }
                    if (method.returnType() != IrType.VOID && value.type() != method.returnType()) {
                        diagnostics.add(returnMismatch(location, "return value type does not match method return type"));
                    }
                });
            } else if (block.terminator().kind() == IrTerminatorKind.THROW) {
                IrValue thrown = block.terminator().value().orElse(null);
                if (thrown == null || !defined.contains(thrown)) {
                    diagnostics.add(Diagnostic.error(
                                    DiagnosticStage.VALIDATION,
                                    IrValidationDiagnostics.IR_USE_BEFORE_DEF,
                                    "IR throw uses value before definition")
                            .at(location));
                } else if (thrown.type() != IrType.REFERENCE) {
                    diagnostics.add(Diagnostic.error(
                                    DiagnosticStage.VALIDATION,
                                    IrValidationDiagnostics.IR_THROW_TYPE_MISMATCH,
                                    "IR throw value must be a reference")
                            .at(location));
                }
            } else if (block.terminator().kind() == IrTerminatorKind.GOTO) {
                validateTargetWithArguments(
                        block.terminator().target().orElse(null),
                        block.terminator().targetArguments(),
                        blocksByName,
                        defined,
                        location,
                        diagnostics);
            } else if (block.terminator().kind() == IrTerminatorKind.BRANCH) {
                IrValue condition = block.terminator().condition().orElse(null);
                if (condition == null || !defined.contains(condition)) {
                    diagnostics.add(Diagnostic.error(
                                    DiagnosticStage.VALIDATION,
                                    IrValidationDiagnostics.IR_USE_BEFORE_DEF,
                                    "IR branch uses condition before definition")
                            .at(location));
                } else if (condition.type() != IrType.I1) {
                    diagnostics.add(returnMismatch(location, "branch condition must be i1"));
                }
                validateTargetWithArguments(
                        block.terminator().trueTarget().orElse(null),
                        block.terminator().trueTargetArguments(),
                        blocksByName,
                        defined,
                        location,
                        diagnostics);
                validateTargetWithArguments(
                        block.terminator().falseTarget().orElse(null),
                        block.terminator().falseTargetArguments(),
                        blocksByName,
                        defined,
                        location,
                        diagnostics);
            } else if (block.terminator().kind() == IrTerminatorKind.SWITCH) {
                IrValue switchValue = block.terminator().switchValue().orElse(null);
                if (switchValue == null || !defined.contains(switchValue)) {
                    diagnostics.add(Diagnostic.error(
                                    DiagnosticStage.VALIDATION,
                                    IrValidationDiagnostics.IR_USE_BEFORE_DEF,
                                    "IR switch uses selector before definition")
                            .at(location));
                } else if (switchValue.type() != IrType.I32) {
                    diagnostics.add(returnMismatch(location, "switch selector must be i32"));
                }
                validateTargetWithArguments(
                        block.terminator().defaultTarget().orElse(null),
                        block.terminator().defaultTargetArguments(),
                        blocksByName,
                        defined,
                        location,
                        diagnostics);
                for (var switchCase : block.terminator().switchCases()) {
                    validateTargetWithArguments(
                            switchCase.target(),
                            switchCase.arguments(),
                            blocksByName,
                            defined,
                            location,
                            diagnostics);
                }
            }
        }
        return diagnostics;
    }

    private void validateTarget(
            String target,
            Set<String> blockNames,
            DiagnosticLocation location,
            List<Diagnostic> diagnostics) {
        if (target == null || !blockNames.contains(target)) {
            diagnostics.add(Diagnostic.error(
                            DiagnosticStage.VALIDATION,
                            IrValidationDiagnostics.IR_USE_BEFORE_DEF,
                            "IR terminator targets missing block: " + target)
                    .at(location));
        }
    }

    private void validateTargetWithArguments(
            String target,
            List<IrValue> arguments,
            Map<String, IrBlock> blocksByName,
            Set<IrValue> defined,
            DiagnosticLocation location,
            List<Diagnostic> diagnostics) {
        if (target == null || !blocksByName.containsKey(target)) {
            validateTarget(target, blocksByName.keySet(), location, diagnostics);
            return;
        }
        IrBlock targetBlock = blocksByName.get(target);
        if (arguments.size() != targetBlock.parameters().size()) {
            diagnostics.add(Diagnostic.error(
                            DiagnosticStage.VALIDATION,
                            IrValidationDiagnostics.IR_BLOCK_ARGUMENT_MISMATCH,
                            "IR terminator passes " + arguments.size()
                                    + " arguments to block " + target
                                    + " with " + targetBlock.parameters().size() + " parameters")
                    .at(location));
            return;
        }
        for (int index = 0; index < arguments.size(); index++) {
            IrValue argument = arguments.get(index);
            if (!defined.contains(argument)) {
                diagnostics.add(Diagnostic.error(
                                DiagnosticStage.VALIDATION,
                                IrValidationDiagnostics.IR_USE_BEFORE_DEF,
                                "IR terminator argument uses value before definition: " + argument.name())
                        .at(location));
            }
            IrValue parameter = targetBlock.parameters().get(index);
            if (argument.type() != parameter.type()) {
                diagnostics.add(Diagnostic.error(
                                DiagnosticStage.VALIDATION,
                                IrValidationDiagnostics.IR_BLOCK_ARGUMENT_MISMATCH,
                                "IR terminator argument type does not match target block parameter type")
                        .at(location));
            }
        }
    }

    private void validateHandlerShape(
            IrBlock block,
            DiagnosticLocation location,
            List<Diagnostic> diagnostics) {
        if (!block.isExceptionHandler()) {
            return;
        }
        if (block.parameters().isEmpty() || block.parameters().get(0).type() != IrType.REFERENCE) {
            diagnostics.add(Diagnostic.error(
                            DiagnosticStage.VALIDATION,
                            IrValidationDiagnostics.IR_EXCEPTION_EDGE_MISMATCH,
                            "IR exception handler block must start with a reference exception parameter")
                    .at(location));
        }
    }

    private void validateExceptionEdges(
            List<xyz.melodysky.ir.model.IrExceptionEdge> exceptionEdges,
            Map<String, IrBlock> blocksByName,
            DiagnosticLocation location,
            List<Diagnostic> diagnostics) {
        for (var edge : exceptionEdges) {
            IrBlock target = blocksByName.get(edge.target());
            if (target == null) {
                diagnostics.add(Diagnostic.error(
                                DiagnosticStage.VALIDATION,
                                IrValidationDiagnostics.IR_EXCEPTION_EDGE_MISMATCH,
                                "IR exception edge targets missing block: " + edge.target())
                        .at(location));
            } else if (!target.isExceptionHandler()) {
                diagnostics.add(Diagnostic.error(
                                DiagnosticStage.VALIDATION,
                                IrValidationDiagnostics.IR_EXCEPTION_EDGE_MISMATCH,
                                "IR exception edge targets non-handler block: " + edge.target())
                        .at(location));
            }
        }
    }

    private void validateMonitorInstruction(
            IrOpcode opcode,
            List<IrValue> operands,
            DiagnosticLocation location,
            List<Diagnostic> diagnostics) {
        if (opcode != IrOpcode.MONITOR_ENTER
                && opcode != IrOpcode.MONITOR_EXIT
                && opcode != IrOpcode.MONITOR_EXIT_ON_EXCEPTION) {
            return;
        }
        if (operands.size() != 1 || operands.get(0).type() != IrType.REFERENCE) {
            diagnostics.add(Diagnostic.error(
                            DiagnosticStage.VALIDATION,
                            IrValidationDiagnostics.IR_MONITOR_TYPE_MISMATCH,
                            "IR monitor helper expects one reference operand")
                    .at(location));
        }
    }

    private void validateClassInitInstruction(
            IrOpcode opcode,
            IrValue result,
            List<IrValue> operands,
            DiagnosticLocation location,
            List<Diagnostic> diagnostics) {
        if (opcode == IrOpcode.CLASS_OBJECT) {
            if (result == null
                    || result.type() != IrType.REFERENCE
                    || operands.size() != 1
                    || operands.get(0).type() != IrType.I64) {
                diagnostics.add(classInitMismatch(location, "IR class object helper expects i64 id and reference result"));
            }
            return;
        }
        if (opcode == IrOpcode.CLASS_INIT_GUARD
                || opcode == IrOpcode.CLASS_INIT_BEGIN
                || opcode == IrOpcode.CLASS_INIT_END
                || opcode == IrOpcode.CLASS_INIT_HAPPENS_BEFORE) {
            if (operands.size() != 1 || operands.get(0).type() != IrType.REFERENCE) {
                diagnostics.add(classInitMismatch(location, "IR class initialization helper expects one class reference"));
            }
            return;
        }
        if (opcode == IrOpcode.CLASS_INIT_FAILED
                && (operands.size() != 2
                        || operands.get(0).type() != IrType.REFERENCE
                        || operands.get(1).type() != IrType.REFERENCE)) {
            diagnostics.add(classInitMismatch(location, "IR class initialization failure helper expects class and exception references"));
        }
    }

    private void validateCallIndirection(
            xyz.melodysky.ir.model.IrInstruction instruction,
            DiagnosticLocation location,
            List<Diagnostic> diagnostics) {
        if (instruction.callIndirection().isEmpty()) {
            return;
        }
        var reference = instruction.callIndirection().orElseThrow();
        xyz.melodysky.ir.model.IrCallInvokeKind opcodeKind;
        try {
            opcodeKind = xyz.melodysky.ir.model.IrCallInvokeKind.fromOpcode(instruction.opcode());
        } catch (IllegalArgumentException exception) {
            diagnostics.add(callIndirectionMismatch(
                    location,
                    "IR call-indirection reference is attached to non-direct call opcode "
                            + instruction.opcode()));
            return;
        }
        if (opcodeKind != reference.originalInvokeKind()) {
            diagnostics.add(callIndirectionMismatch(
                    location,
                    "IR call-indirection invoke kind does not match the original call opcode"));
        }
        xyz.melodysky.ir.model.IrCallSignature actual =
                xyz.melodysky.ir.model.IrCallSignature.fromInstruction(instruction);
        if (!actual.equals(reference.signature())) {
            diagnostics.add(callIndirectionMismatch(
                    location,
                    "IR call-indirection signature does not match call operands/result"));
        }
        if (instruction.symbol().isEmpty()) {
            diagnostics.add(callIndirectionMismatch(
                    location,
                    "IR call-indirection instruction must retain its semantic target symbol"));
        }
    }

    private Diagnostic callIndirectionMismatch(DiagnosticLocation location, String message) {
        return Diagnostic.error(
                        DiagnosticStage.VALIDATION,
                        IrValidationDiagnostics.IR_CALL_INDIRECTION_MISMATCH,
                        message)
                .at(location);
    }

    private Diagnostic classInitMismatch(DiagnosticLocation location, String message) {
        return Diagnostic.error(
                        DiagnosticStage.VALIDATION,
                        IrValidationDiagnostics.IR_CLASS_INIT_TYPE_MISMATCH,
                        message)
                .at(location);
    }

    private Diagnostic returnMismatch(DiagnosticLocation location, String message) {
        return Diagnostic.error(
                        DiagnosticStage.VALIDATION,
                        IrValidationDiagnostics.IR_RETURN_TYPE_MISMATCH,
                        message)
                .at(location);
    }
}
