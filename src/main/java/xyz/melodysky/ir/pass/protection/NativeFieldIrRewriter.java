package xyz.melodysky.ir.pass.protection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import xyz.melodysky.analysis.field.NativeFieldInternalizationPlan;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticCode;
import xyz.melodysky.diagnostic.DiagnosticSeverity;
import xyz.melodysky.diagnostic.DiagnosticStage;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.NativeFieldSlotRef;
import xyz.melodysky.ir.validate.IrMethodValidator;

/**
 * Replaces only plan-approved static field instructions with opaque native
 * slot operations. Class-initialization guards remain separate IR
 * instructions and are therefore preserved.
 */
public final class NativeFieldIrRewriter {
    private static final DiagnosticCode ACCESS_MISMATCH =
            DiagnosticCode.of("FIELD_INTERNALIZATION_ACCESS_MISMATCH");

    public NativeFieldIrRewriteResult rewrite(
            Map<String, IrMethod> input,
            NativeFieldInternalizationPlan plan) {
        java.util.LinkedHashSet<String> llvmMethodKeys =
                new java.util.LinkedHashSet<>(input.keySet());
        plan.internalizedFields().stream()
                .flatMap(decision -> decision.accesses().stream())
                .map(access -> access.methodKey())
                .forEach(llvmMethodKeys::add);
        return rewrite(input, plan, llvmMethodKeys);
    }

    public NativeFieldIrRewriteResult rewrite(
            Map<String, IrMethod> input,
            NativeFieldInternalizationPlan plan,
            Set<String> llvmMethodKeys) {
        llvmMethodKeys = Set.copyOf(llvmMethodKeys);
        Map<String, NativeFieldSlotRef> slotByField = plan.nativeStoredFields().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        decision -> decision.field().fieldKey(),
                        decision -> new NativeFieldSlotRef(
                                plan.storageKind(decision),
                                decision.nativeSlotId().orElseThrow(),
                                plan.referenceIndex(decision))));
        Set<String> plannedAccessorMethodKeys = plan.internalizedFields().stream()
                .flatMap(decision -> decision.accesses().stream())
                .map(access -> access.methodKey())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Map<String, xyz.melodysky.analysis.field.NativeFieldInternalizationDecision>
                constantByField = plan.constantFoldedFields().stream()
                        .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                decision -> decision.field().fieldKey(),
                                decision -> decision));
        if (slotByField.isEmpty() && constantByField.isEmpty()) {
            return new NativeFieldIrRewriteResult(input, List.of(), List.of(), List.of());
        }
        NativeFieldIrAccessVerifier accessVerifier = new NativeFieldIrAccessVerifier();
        NativeConstantFieldIrFolder constantFolder =
                new NativeConstantFieldIrFolder();
        List<String> inputAccessIssues = java.util.stream.Stream.concat(
                        accessVerifier.verifyInput(
                                        input,
                                        plan,
                                        llvmMethodKeys)
                                .stream(),
                        constantFolder.verifyInput(
                                        input,
                                        plan,
                                        llvmMethodKeys)
                                .stream())
                .sorted()
                .toList();
        if (!inputAccessIssues.isEmpty()) {
            return failed(input, ACCESS_MISMATCH, inputAccessIssues);
        }

        LinkedHashMap<String, IrMethod> rewritten = new LinkedHashMap<>();
        ArrayList<String> affectedMethods = new ArrayList<>();
        ArrayList<String> affectedSlots = new ArrayList<>();
        IrMethodValidator validator = new IrMethodValidator();
        List<IrMethod> sortedMethods = input.values().stream()
                .sorted(java.util.Comparator.comparing(IrMethod::methodKey))
                .toList();
        List<String> invalidInputs = sortedMethods.stream()
                .filter(method -> plannedAccessorMethodKeys.contains(method.methodKey()))
                .filter(method -> hasError(validator.validate(method)))
                .map(method -> "field internalization received invalid IR for " + method.methodKey())
                .toList();
        if (!invalidInputs.isEmpty()) {
            return failed(
                    input,
                    DiagnosticCode.of("FIELD_INTERNALIZATION_INPUT_IR_INVALID"),
                    invalidInputs);
        }
        for (IrMethod method : sortedMethods) {
            RewriteMethodResult candidate = llvmMethodKeys.contains(method.methodKey())
                    ? rewriteMethod(method, slotByField, constantByField, constantFolder)
                    : new RewriteMethodResult(method, List.of(), false);
            rewritten.put(method.methodKey(), candidate.method());
            if (candidate.changed()) {
                affectedMethods.add(method.methodKey());
                affectedSlots.addAll(candidate.slots());
            }
        }
        List<String> invalidOutputs = rewritten.values().stream()
                .filter(method -> plannedAccessorMethodKeys.contains(method.methodKey()))
                .filter(method -> hasError(validator.validate(method)))
                .map(method -> "field internalization produced invalid IR for " + method.methodKey())
                .sorted()
                .toList();
        if (!invalidOutputs.isEmpty()) {
            return failed(
                    input,
                    DiagnosticCode.of("FIELD_INTERNALIZATION_OUTPUT_IR_INVALID"),
                    invalidOutputs);
        }
        List<String> outputAccessIssues = java.util.stream.Stream.concat(
                        accessVerifier.verifyOutput(
                                        rewritten,
                                        plan,
                                        llvmMethodKeys)
                                .stream(),
                        constantFolder.verifyOutput(rewritten, plan).stream())
                .sorted()
                .toList();
        if (!outputAccessIssues.isEmpty()) {
            return failed(input, ACCESS_MISMATCH, outputAccessIssues);
        }
        return new NativeFieldIrRewriteResult(
                rewritten,
                affectedMethods,
                affectedSlots,
                List.of());
    }

    private RewriteMethodResult rewriteMethod(
            IrMethod method,
            Map<String, NativeFieldSlotRef> slotByField,
            Map<String, xyz.melodysky.analysis.field.NativeFieldInternalizationDecision>
                    constantByField,
            NativeConstantFieldIrFolder constantFolder) {
        boolean changed = false;
        ArrayList<String> slots = new ArrayList<>();
        ArrayList<IrBlock> blocks = new ArrayList<>();
        for (IrBlock block : method.blocks()) {
            ArrayList<IrInstruction> instructions = new ArrayList<>();
            for (IrInstruction instruction : block.instructions()) {
                var constant = instruction.symbol()
                        .map(constantByField::get)
                        .orElse(null);
                if (constant != null && instruction.opcode() == IrOpcode.GET_STATIC) {
                    instructions.addAll(constantFolder
                            .fold(instruction, constant)
                            .instructions());
                    changed = true;
                    continue;
                }
                NativeFieldSlotRef slot = instruction.symbol().map(slotByField::get).orElse(null);
                if (slot == null
                        || (instruction.opcode() != IrOpcode.GET_STATIC
                                && instruction.opcode() != IrOpcode.PUT_STATIC)) {
                    instructions.add(instruction);
                    continue;
                }
                IrOpcode opcode = instruction.opcode() == IrOpcode.GET_STATIC
                        ? IrOpcode.GET_NATIVE_STATIC
                        : IrOpcode.PUT_NATIVE_STATIC;
                instructions.add(new IrInstruction(
                        instruction.result(),
                        opcode,
                        instruction.operands(),
                        instruction.intLiteral(),
                        instruction.longLiteral(),
                        instruction.floatLiteral(),
                        instruction.doubleLiteral(),
                        Optional.of(slot.encoded()),
                        instruction.exceptionSites(),
                        instruction.callIndirection()));
                slots.add(slot.opaqueSlotId());
                changed = true;
            }
            blocks.add(new IrBlock(
                    block.name(),
                    block.parameters(),
                    block.exceptionCatchTypes(),
                    block.exceptionEdges(),
                    instructions,
                    block.terminator()));
        }
        if (!changed) {
            return new RewriteMethodResult(method, List.of(), false);
        }
        return new RewriteMethodResult(new IrMethod(
                method.owner(),
                method.name(),
                method.descriptor(),
                method.returnType(),
                method.parameters(),
                blocks), slots, true);
    }

    private boolean hasError(List<Diagnostic> diagnostics) {
        return diagnostics.stream().anyMatch(diagnostic ->
                diagnostic.severity() == DiagnosticSeverity.ERROR);
    }

    private NativeFieldIrRewriteResult failed(
            Map<String, IrMethod> input,
            DiagnosticCode code,
            List<String> messages) {
        List<Diagnostic> diagnostics = messages.stream()
                .sorted()
                .map(message -> Diagnostic.error(DiagnosticStage.PROTECTION, code, message))
                .toList();
        return new NativeFieldIrRewriteResult(input, List.of(), List.of(), diagnostics);
    }

    private record RewriteMethodResult(
            IrMethod method,
            List<String> slots,
            boolean changed) {
    }
}
