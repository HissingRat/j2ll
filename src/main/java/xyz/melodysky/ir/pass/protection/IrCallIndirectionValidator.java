package xyz.melodysky.ir.pass.protection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticLocation;
import xyz.melodysky.diagnostic.DiagnosticStage;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrCallInvokeKind;
import xyz.melodysky.ir.model.IrCallSignature;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrProgram;
import xyz.melodysky.ir.validate.IrMethodValidator;
import xyz.melodysky.ir.validate.IrValidationDiagnostics;

/**
 * Cross-checks instruction metadata against the program-level indirection
 * plan and the explicit native-implementation eligibility boundary.
 */
public final class IrCallIndirectionValidator {
    public List<Diagnostic> validate(
            IrProgram program,
            IrCallIndirectionPlan plan,
            IrNativeDirectTargets nativeDirectTargets) {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(nativeDirectTargets, "nativeDirectTargets");

        ArrayList<Diagnostic> diagnostics = new ArrayList<>();
        Map<String, IrMethod> methodsByKey = methodsByKey(program);
        for (IrMethod method : methodsByKey.values()) {
            diagnostics.addAll(new IrMethodValidator().validate(method));
        }
        validateTargets(plan, nativeDirectTargets, methodsByKey, diagnostics);
        validateSites(program, plan, nativeDirectTargets, methodsByKey, diagnostics);
        return diagnostics.stream().sorted().toList();
    }

    private void validateTargets(
            IrCallIndirectionPlan plan,
            IrNativeDirectTargets nativeDirectTargets,
            Map<String, IrMethod> methodsByKey,
            List<Diagnostic> diagnostics) {
        for (IrCallIndirectionGroup group : plan.groups()) {
            for (IrCallIndirectionTarget target : group.targets()) {
                IrMethod targetMethod = methodsByKey.get(target.targetMethodKey());
                if (targetMethod == null) {
                    diagnostics.add(error(
                            DiagnosticLocation.none(),
                            "IR call-indirection plan targets method outside the IR program"));
                    continue;
                }
                DiagnosticLocation location = location(targetMethod);
                if (!nativeDirectTargets.contains(targetMethod.methodKey())) {
                    diagnostics.add(error(
                            location,
                            "IR call-indirection plan targets a method without LLVM native-path proof"));
                }
                if (!group.signature().equals(IrCallSignature.fromMethod(targetMethod))) {
                    diagnostics.add(error(
                            location,
                            "IR call-indirection target signature does not match its signature group"));
                }
            }
        }
    }

    private void validateSites(
            IrProgram program,
            IrCallIndirectionPlan plan,
            IrNativeDirectTargets nativeDirectTargets,
            Map<String, IrMethod> methodsByKey,
            List<Diagnostic> diagnostics) {
        for (IrCallIndirectionSite site : plan.sites()) {
            IrMethod caller = methodsByKey.get(site.siteId().callerMethodKey());
            if (caller == null) {
                diagnostics.add(error(
                        DiagnosticLocation.none(),
                        "IR call-indirection plan references a missing caller method"));
                continue;
            }
            DiagnosticLocation location = location(caller)
                    .withInstructionOffset(site.siteId().instructionIndex());
            if (!nativeDirectTargets.contains(caller.methodKey())) {
                diagnostics.add(error(
                        location,
                        "IR call-indirection site belongs to a caller without LLVM native-path proof"));
            }
            IrBlock block = caller.blocks().stream()
                    .filter(candidate -> candidate.name().equals(site.siteId().blockName()))
                    .findFirst()
                    .orElse(null);
            if (block == null
                    || site.siteId().instructionIndex() >= block.instructions().size()) {
                diagnostics.add(error(
                        location,
                        "IR call-indirection plan references a missing instruction"));
                continue;
            }
            IrInstruction instruction =
                    block.instructions().get(site.siteId().instructionIndex());
            if (instruction.callIndirection().filter(site.reference()::equals).isEmpty()) {
                diagnostics.add(error(
                        location,
                        "IR call-indirection instruction metadata does not match its plan site"));
            }
            validateSiteSemantics(plan, site, instruction, block, location, diagnostics);
        }

        for (var irClass : program.classes()) {
            for (IrMethod method : irClass.methods()) {
                for (IrBlock block : method.blocks()) {
                    for (int index = 0; index < block.instructions().size(); index++) {
                        IrInstruction instruction = block.instructions().get(index);
                        if (instruction.callIndirection().isEmpty()) {
                            continue;
                        }
                        IrCallSiteId siteId =
                                new IrCallSiteId(method.methodKey(), block.name(), index);
                        if (plan.site(siteId).isEmpty()) {
                            diagnostics.add(error(
                                    location(method).withInstructionOffset(index),
                                    "IR instruction references call-indirection metadata outside the active plan"));
                        }
                    }
                }
            }
        }
    }

    private void validateSiteSemantics(
            IrCallIndirectionPlan plan,
            IrCallIndirectionSite site,
            IrInstruction instruction,
            IrBlock block,
            DiagnosticLocation location,
            List<Diagnostic> diagnostics) {
        if (!site.reference().planId().equals(plan.planId())
                || site.reference().mode() != plan.mode()) {
            diagnostics.add(error(
                    location,
                    "IR call-indirection reference points at another plan or mode"));
        }
        IrCallInvokeKind actualKind;
        try {
            actualKind = IrCallInvokeKind.fromOpcode(instruction.opcode());
        } catch (IllegalArgumentException exception) {
            diagnostics.add(error(
                    location,
                    "IR call-indirection plan site no longer contains a direct call"));
            return;
        }
        if (actualKind != site.semantics().originalInvokeKind()) {
            diagnostics.add(error(
                    location,
                    "IR call-indirection rewrite changed the original invoke kind"));
        }
        if (site.semantics().nativeTargetUnavailable()
                || !site.semantics().exceptionPropagationPreserved()) {
            diagnostics.add(error(
                    location,
                    "IR call-indirection site does not preserve native target/exception semantics"));
        }
        if (actualKind.hasReceiver() != site.semantics().receiverNullCheckRequired()) {
            diagnostics.add(error(
                    location,
                    "IR call-indirection receiver null-check semantics do not match invoke kind"));
        }
        var target = plan.target(site.reference().entryId()).orElse(null);
        if (target == null) {
            diagnostics.add(error(location, "IR call-indirection site targets a missing plan entry"));
            return;
        }
        if ((actualKind == IrCallInvokeKind.STATIC || actualKind == IrCallInvokeKind.SPECIAL)
                && instruction.symbol().filter(target.targetMethodKey()::equals).isEmpty()) {
            diagnostics.add(error(
                    location,
                    "IR bytecode-direct call target no longer matches its indirection entry"));
        }
        if (site.semantics().classInitializationGuardRequired()) {
            String targetOwner = owner(target.targetMethodKey());
            if (!hasClassInitializationGuard(
                    block,
                    site.siteId().instructionIndex(),
                    targetOwner)) {
                diagnostics.add(error(
                        location,
                        "IR call-indirection site lost its required class-initialization guard"));
            }
        }
    }

    private boolean hasClassInitializationGuard(
            IrBlock block,
            int callInstructionIndex,
            String targetOwner) {
        String expectedPrefix = "class:L" + targetOwner + ";";
        for (int index = 0; index < callInstructionIndex; index++) {
            IrInstruction instruction = block.instructions().get(index);
            if (instruction.opcode() == IrOpcode.CLASS_INIT_GUARD
                    && instruction.symbol()
                            .filter(symbol -> symbol.startsWith(expectedPrefix))
                            .isPresent()) {
                return true;
            }
        }
        return false;
    }

    private Map<String, IrMethod> methodsByKey(IrProgram program) {
        LinkedHashMap<String, IrMethod> methods = new LinkedHashMap<>();
        program.classes().stream()
                .flatMap(irClass -> irClass.methods().stream())
                .sorted(Comparator.comparing(IrMethod::methodKey))
                .forEach(method -> {
                    if (methods.put(method.methodKey(), method) != null) {
                        throw new IllegalArgumentException(
                                "duplicate IR method key " + method.methodKey());
                    }
                });
        return Map.copyOf(methods);
    }

    private Diagnostic error(DiagnosticLocation location, String message) {
        return Diagnostic.error(
                        DiagnosticStage.VALIDATION,
                        IrValidationDiagnostics.IR_CALL_INDIRECTION_MISMATCH,
                        message)
                .at(location);
    }

    private DiagnosticLocation location(IrMethod method) {
        return DiagnosticLocation.methodLocation(
                method.owner(),
                method.name(),
                method.descriptor());
    }

    private String owner(String methodKey) {
        int separator = methodKey.indexOf('#');
        return separator <= 0 ? "" : methodKey.substring(0, separator);
    }
}
